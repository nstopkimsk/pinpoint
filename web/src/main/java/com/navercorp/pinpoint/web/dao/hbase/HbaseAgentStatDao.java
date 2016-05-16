/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.web.dao.hbase;

import static com.navercorp.pinpoint.common.hbase.HBaseTables.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import com.navercorp.pinpoint.common.hbase.HBaseTables;
import com.navercorp.pinpoint.common.hbase.HbaseOperations2;
import com.navercorp.pinpoint.common.hbase.ResultsExtractor;
import com.navercorp.pinpoint.common.hbase.RowMapper;
import com.navercorp.pinpoint.common.util.BytesUtils;
import com.navercorp.pinpoint.common.util.RowKeyUtils;
import com.navercorp.pinpoint.common.util.TimeUtils;
import com.navercorp.pinpoint.web.dao.AgentStatDao;
import com.navercorp.pinpoint.web.util.AgentStats;
import com.navercorp.pinpoint.web.vo.AgentStat;
import com.navercorp.pinpoint.web.vo.Range;
import com.sematext.hbase.wd.AbstractRowKeyDistributor;

/**
 * @author emeroad
 * @author hyungil.jeong
 */
@Repository
public class HbaseAgentStatDao implements AgentStatDao {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private HbaseOperations2 hbaseOperations2;

    @Autowired
    @Qualifier("agentStatMapper")
    private RowMapper<List<AgentStat>> agentStatMapper;

    @Autowired
    @Qualifier("agentStatRowKeyDistributor")
    private AbstractRowKeyDistributor rowKeyDistributor;

    private int scanCacheSize = 256;

    public void setScanCacheSize(int scanCacheSize) {
        this.scanCacheSize = scanCacheSize;
    }

    @Override
    public List<AgentStat> getAgentStatList(String agentId, Range range) {
        if (agentId == null) {
            throw new NullPointerException("agentId must not be null");
        }
        if (range == null) {
            throw new NullPointerException("range must not be null");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("scanAgentStat : agentId={}, {}", agentId, range);
        }
        
        return getAgentStatListFromRaw(agentId, range);
    }
    
    private List<AgentStat> getAgentStatListFromRaw(String agentId, Range range) {
        Scan scan = createScan(agentId, range);
        scan.addFamily(HBaseTables.AGENT_STAT_CF_STATISTICS);

        List<List<AgentStat>> intermediate = hbaseOperations2.find(HBaseTables.AGENT_STAT, scan, rowKeyDistributor, agentStatMapper);

        int expectedSize = (int) (range.getRange() / 5000); // data for 5 seconds
        List<AgentStat> merged = new ArrayList<>(expectedSize);

        for (List<AgentStat> each : intermediate) {
            merged.addAll(each);
        }

        return merged;
    }
    
    public List<AgentStat> getAggregatedAgentStatList(String agentId, Range range) {
        if (agentId == null) {
            throw new NullPointerException("agentId must not be null");
        }
        if (range == null) {
            throw new NullPointerException("range must not be null");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("scanAgentStat : agentId={}, {}", agentId, range);
        }

        Scan scan = createScan(agentId, range);
        scan.addFamily(HBaseTables.AGENT_STAT_CF_STATISTICS);
        
        List<List<AgentStat>> intermediate = hbaseOperations2.find(HBaseTables.AGENT_STAT_AGGR, scan, rowKeyDistributor, agentStatMapper);
        
        List<AgentStat> merged = new ArrayList<>();

        for (List<AgentStat> each : intermediate) {
            merged.addAll(each);
        }
        
        Collections.sort(merged, AgentStats.TIMESTAMP_COMPARATOR);
        
        
        List<Range> missingRanges = new ArrayList<>();
        long last = range.getFrom();
        
        for (AgentStat stat : merged) {
            if (last + AgentStat.AGGR_SAMPLE_INTERVAL * 2 < stat.getTimestamp()) {
                Range r = new Range(last, stat.getTimestamp() - stat.getCollectInterval());
                missingRanges.add(r);
            }
            
            last = stat.getTimestamp();
        }
        
        if (last + AgentStat.AGGR_SAMPLE_INTERVAL * 2 < range.getTo()) {
            Range r = new Range(last, range.getTo());
            missingRanges.add(r);
        }
        
        for (Range r : missingRanges) {
            logger.debug("AgentStatAggr doesn't have range: " + r.prettyToString() + " of " + agentId);

            List<AgentStat> list = getAgentStatListFromRaw(agentId, r);
            
            if (list.isEmpty()) {
                logger.debug("AgentStat also doesn't have range: " + r.prettyToString() + " of " + agentId);
                continue;
            }
            
            List<AgentStat> aggregated = AgentStats.aggregate(list, AgentStat.AGGR_SAMPLE_INTERVAL);
            merged.addAll(aggregated);
        }

        return merged;
    }
    
    @Override
    public boolean agentStatExists(String agentId, Range range) {
        if (agentId == null) {
            throw new NullPointerException("agentId must not be null");
        }
        if (range == null) {
            throw new NullPointerException("range must not be null");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("checking for stat data existence : agentId={}, {}", agentId, range);
        }

        Scan scan = createScan(agentId, range);
        scan.setCaching(1);
        scan.addFamily(HBaseTables.AGENT_STAT_CF_STATISTICS);

        return hbaseOperations2.find(HBaseTables.AGENT_STAT, scan, rowKeyDistributor, new AgentStatDataExistsResultsExtractor(this.agentStatMapper));
    }

    private class AgentStatDataExistsResultsExtractor implements ResultsExtractor<Boolean> {

        private final RowMapper<List<AgentStat>> agentStatMapper;

        private AgentStatDataExistsResultsExtractor(RowMapper<List<AgentStat>> agentStatMapper) {
            this.agentStatMapper = agentStatMapper;
        }

        @Override
        public Boolean extractData(ResultScanner results) throws Exception {
            int matchCnt = 0;
            for (Result result : results) {
                if (!result.isEmpty()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("stat data exists, most recent data : {}", this.agentStatMapper.mapRow(result, matchCnt++));
                    }
                    return true;
                } else {
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * make a row key based on timestamp
     * FIXME there is the same duplicate code at collector's dao module
     */
    private byte[] getRowKey(String agentId, long timestamp) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId must not null");
        }
        byte[] bAgentId = BytesUtils.toBytes(agentId);
        return RowKeyUtils.concatFixedByteAndLong(bAgentId, AGENT_NAME_MAX_LEN, TimeUtils.reverseTimeMillis(timestamp));
    }

    private Scan createScan(String agentId, Range range) {
        Scan scan = new Scan();
        scan.setCaching(this.scanCacheSize);

        byte[] startKey = getRowKey(agentId, range.getFrom());
        byte[] endKey = getRowKey(agentId, range.getTo());

        // start key is replaced by end key because key has been reversed
        scan.setStartRow(endKey);
        scan.setStopRow(startKey);

        //        scan.addColumn(HBaseTables.AGENT_STAT_CF_STATISTICS, HBaseTables.AGENT_STAT_CF_STATISTICS_V1);
        scan.addFamily(HBaseTables.AGENT_STAT_CF_STATISTICS);
        scan.setId("AgentStatScan");

        // toString() method of Scan converts a message to json format so it is slow for the first time.
        logger.debug("create scan:{}", scan);
        return scan;
    }

    //    public List<AgentStat> scanAgentStatList(String agentId, long start, long end, final int limit) {
    //        if (logger.isDebugEnabled()) {
    //            logger.debug("scanAgentStatList");
    //        }
    //        Scan scan = createScan(agentId, start, end);
    //
    //        List<AgentStat> list = hbaseOperations2.find(HBaseTables.AGENT_STAT, scan, rowKeyDistributor, new ResultsExtractor<List<AgentStat>>() {
    //            @Override
    //            public List<AgentStat> extractData(ResultScanner results) throws Exception {
    //                TDeserializer deserializer = new TDeserializer();
    //                List<AgentStat> list = new ArrayList<AgentStat>();
    //                for (Result result : results) {
    //                    if (result == null) {
    //                        continue;
    //                    }
    //
    //                    if (list.size() >= limit) {
    //                        break;
    //                    }
    //
    //                    for (KeyValue kv : result.raw()) {
    //                        AgentStat agentStat = new AgentStat();
    //                        deserializer.deserialize(agentStat, kv.getBuffer());
    //                        list.add(agentStat);
    //                    }
    //                }
    //                return list;
    //            }
    //        });
    //        return list;
    //    }

}
