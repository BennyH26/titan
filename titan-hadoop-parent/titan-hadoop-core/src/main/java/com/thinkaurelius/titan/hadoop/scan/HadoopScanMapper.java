package com.thinkaurelius.titan.hadoop.scan;

import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.diskstorage.Entry;
import com.thinkaurelius.titan.diskstorage.EntryList;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.configuration.ConfigNamespace;
import com.thinkaurelius.titan.diskstorage.configuration.Configuration;
import com.thinkaurelius.titan.diskstorage.configuration.ModifiableConfiguration;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.SliceQuery;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.scan.ScanJob;
import com.thinkaurelius.titan.diskstorage.util.BufferUtil;
import com.thinkaurelius.titan.diskstorage.util.EntryArrayList;
import com.thinkaurelius.titan.hadoop.config.ModifiableHadoopConfiguration;
import com.thinkaurelius.titan.hadoop.config.TitanHadoopConfiguration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Predicate;

import static com.thinkaurelius.titan.hadoop.compat.HadoopCompatLoader.DEFAULT_COMPAT;

/**
 * Run a {@link com.thinkaurelius.titan.diskstorage.keycolumnvalue.scan.ScanJob}
 * via a Hadoop {@link org.apache.hadoop.mapreduce.Mapper} over the edgestore.
 */
public class HadoopScanMapper extends Mapper<StaticBuffer, Iterable<Entry>, NullWritable, NullWritable> {

    private static final Logger log = LoggerFactory.getLogger(HadoopScanMapper.class);

    protected ScanJob job;
    protected HadoopContextScanMetrics metrics;
    protected com.thinkaurelius.titan.diskstorage.configuration.Configuration jobConf;
    private Predicate<StaticBuffer> keyFilter;
    private SliceQuery initialQuery;
    private List<SliceQuery> subsequentQueries;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        super.setup(context);
        org.apache.hadoop.conf.Configuration hadoopConf = DEFAULT_COMPAT.getContextConfiguration(context);
        ModifiableHadoopConfiguration scanConf = ModifiableHadoopConfiguration.of(TitanHadoopConfiguration.MAPRED_NS, hadoopConf);
        job = getJob(scanConf);
        metrics = new HadoopContextScanMetrics(context);
        Configuration graphConf = getTitanConfiguration(context);
        finishSetup(scanConf, graphConf);
    }

    protected void finishSetup(ModifiableHadoopConfiguration scanConf, Configuration graphConf) {
        jobConf = getJobConfiguration(scanConf);
        Preconditions.checkNotNull(metrics);
        // Allowed to be null for jobs that specify no configuration and no configuration root
        //Preconditions.checkNotNull(jobConf);
        Preconditions.checkNotNull(job);
        job.setup(jobConf, graphConf, metrics);
        keyFilter = job.getKeyFilter();
        List<SliceQuery> sliceQueries = job.getQueries();
        Preconditions.checkArgument(null != sliceQueries, "Job cannot specify null query list");
        Preconditions.checkArgument(0 < sliceQueries.size(), "Job must specify at least one query");
        // Assign head of getQueries() to "initialQuery"
        initialQuery = sliceQueries.get(0);
        // Assign tail of getQueries() to "subsequentQueries"
        subsequentQueries = new ArrayList<>(sliceQueries.subList(1,sliceQueries.size()));
        Preconditions.checkState(sliceQueries.size() == subsequentQueries.size() + 1);
        Preconditions.checkNotNull(initialQuery);

        if (0 < subsequentQueries.size()) {
            //It is assumed that the first query is the grounding query if multiple queries exist
            StaticBuffer start = initialQuery.getSliceStart();
            Preconditions.checkArgument(start.equals(BufferUtil.zeroBuffer(start.length())),
                    "Expected start of first query to be all 0s: %s", start);
            StaticBuffer end = initialQuery.getSliceEnd();
            Preconditions.checkArgument(end.equals(BufferUtil.oneBuffer(end.length())),
                    "Expected end of first query to be all 1s: %s", end);
        }
    }

    @Override
    protected void map(StaticBuffer key, Iterable<Entry> values, Context context) throws IOException, InterruptedException {
        EntryArrayList al = EntryArrayList.of(values);

        // KeyFilter check
        if (!keyFilter.test(key)) {
            log.debug("Skipping key {} based on KeyFilter", key);
            return;
        }

        // InitialQuery check (at least one match is required or else the key is ignored)
        EntryList initialQueryMatches = findEntriesMatchingQuery(initialQuery, al);
        if (0 == initialQueryMatches.size()) {
            log.debug("Skipping key {} based on InitialQuery ({}) match failure", key, initialQuery);
            return;
        }

        // Both conditions (KeyFilter && InitialQuery) for invoking process are satisfied

        // Create an entries parameter to be passed into the process method
        Map<SliceQuery, EntryList> matches = new HashMap<>();
        matches.put(initialQuery, initialQueryMatches);

        // Find matches (if any are present) for noninitial queries
        for (SliceQuery sq : subsequentQueries) {
            matches.put(sq, findEntriesMatchingQuery(sq, al));
        }

        // Process
        job.process(key, matches, metrics);
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        super.cleanup(context);
        job.teardown(metrics);
    }

    private EntryList findEntriesMatchingQuery(SliceQuery query, EntryList sortedEntries) {

        int lowestStartMatch = sortedEntries.size(); // Inclusive
        int highestEndMatch = -1; // Inclusive

        final StaticBuffer queryStart = query.getSliceStart();
        final StaticBuffer queryEnd = query.getSliceEnd();

        // Find the lowest matchStart s.t. query.getSliceStart <= sortedEntries.get(matchStart)

        int low = 0;
        int high = sortedEntries.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            Entry midVal = sortedEntries.get(mid);

            int cmpStart = queryStart.compareTo(midVal.getColumn());

            if (0 < cmpStart) {
                // query lower bound exceeds entry (no match)
                if (lowestStartMatch == mid + 1) {
                    // lowestStartMatch located
                    break;
                }
                // Move to higher list index
                low = mid + 1;
            } else /* (0 >= cmpStart) */ {
                // entry equals or exceeds query lower bound (match, but not necessarily lowest match)
                if (mid < lowestStartMatch) {
                    lowestStartMatch = mid;
                }
                // Move to a lower list index
                high = mid - 1;
            }
        }

        // If lowestStartMatch is beyond the end of our list parameter, there cannot possibly be any matches,
        // so we can bypass the highestEndMatch search and just return an empty result.
        if (sortedEntries.size() == lowestStartMatch) {
            return EntryList.EMPTY_LIST;
        }

        // Find the highest matchEnd s.t. sortedEntries.get(matchEnd) < query.getSliceEnd

        low = 0;
        high = sortedEntries.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            Entry midVal = sortedEntries.get(mid);

            int cmpEnd = queryEnd.compareTo(midVal.getColumn());

            if (0 < cmpEnd) {
                // query upper bound exceeds entry (match, not necessarily highest)
                if (mid > highestEndMatch) {
                    highestEndMatch = mid;
                }
                // Move to higher list index
                low = mid + 1;
            } else /* (0 >= cmpEnd) */ {
                // entry equals or exceeds query upper bound (no match)
                if (highestEndMatch == mid - 1) {
                    // highestEndMatch located
                    break;
                }
                // Move to a lower list index
                high = mid - 1;
            }
        }

        if (0 <= highestEndMatch - lowestStartMatch) {
            // Return sublist between indices (inclusive at both indices)
            int endIndex = highestEndMatch + 1; // This will be passed into subList, which interprets it exclusively
            if (query.hasLimit()) {
                endIndex = Math.min(endIndex, query.getLimit() + lowestStartMatch);
            }
            // TODO avoid unnecessary copy here
            return EntryArrayList.of(sortedEntries.subList(lowestStartMatch, endIndex /* exclusive */));
        } else {
            return EntryList.EMPTY_LIST;
        }
    }

    private ScanJob getJob(Configuration scanConf) {
        String jobClass = scanConf.get(TitanHadoopConfiguration.SCAN_JOB_CLASS);

        try {
            return (ScanJob)Class.forName(jobClass).newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    static ModifiableConfiguration getTitanConfiguration(Context context) {
        org.apache.hadoop.conf.Configuration hadoopConf = DEFAULT_COMPAT.getContextConfiguration(context);
        return ModifiableHadoopConfiguration.of(TitanHadoopConfiguration.MAPRED_NS, hadoopConf).getTitanInputConf();
    }

    static Configuration getJobConfiguration(ModifiableHadoopConfiguration scanConf) {
        if (!scanConf.has(TitanHadoopConfiguration.SCAN_JOB_CONFIG_ROOT)) {
            log.debug("No job configuration root provided");
            return null;
        }
        ConfigNamespace jobRoot = getJobRoot(scanConf.get(TitanHadoopConfiguration.SCAN_JOB_CONFIG_ROOT));
        return ModifiableHadoopConfiguration.subset(jobRoot, TitanHadoopConfiguration.SCAN_JOB_CONFIG_KEYS, scanConf);
    }

    static ConfigNamespace getJobRoot(String confRootName) {

        String tokens[] = confRootName.split("#");
        String className = tokens[0];
        String fieldName = tokens[1];

        try {
            Field f = Class.forName(className).getField(fieldName);
            return (ConfigNamespace)f.get(null);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}