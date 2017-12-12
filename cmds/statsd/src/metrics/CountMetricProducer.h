/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef COUNT_METRIC_PRODUCER_H
#define COUNT_METRIC_PRODUCER_H

#include <unordered_map>

#include <android/util/ProtoOutputStream.h>
#include <gtest/gtest_prod.h>
#include "../anomaly/AnomalyTracker.h"
#include "../condition/ConditionTracker.h"
#include "../matchers/matcher_util.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

struct CountBucket {
    int64_t mBucketStartNs;
    int64_t mBucketEndNs;
    int64_t mCount;
    uint64_t mBucketNum;
};

class CountMetricProducer : public MetricProducer {
public:
    // TODO: Pass in the start time from MetricsManager, it should be consistent for all metrics.
    CountMetricProducer(const ConfigKey& key, const CountMetric& countMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const uint64_t startTimeNs);

    virtual ~CountMetricProducer();

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int64_t version)
            override{};
    // TODO: Implement this later.
    virtual void notifyAppRemoved(const string& apk, const int uid) override{};

protected:
    void onMatchedLogEventInternalLocked(
            const size_t matcherIndex, const HashableDimensionKey& eventKey,
            const std::map<std::string, HashableDimensionKey>& conditionKey, bool condition,
            const LogEvent& event, bool scheduledPull) override;

private:
    void onDumpReportLocked(const uint64_t dumpTimeNs,
                            android::util::ProtoOutputStream* protoOutput) override;

    // Internal interface to handle condition change.
    void onConditionChangedLocked(const bool conditionMet, const uint64_t eventTime) override;

    // Internal interface to handle sliced condition change.
    void onSlicedConditionMayChangeLocked(const uint64_t eventTime) override;

    // Internal function to calculate the current used bytes.
    size_t byteSizeLocked() const override;

    // Util function to flush the old packet.
    void flushIfNeededLocked(const uint64_t& newEventTime);

    const CountMetric mMetric;

    // TODO: Add a lock to mPastBuckets.
    std::unordered_map<HashableDimensionKey, std::vector<CountBucket>> mPastBuckets;

    // The current bucket.
    std::shared_ptr<DimToValMap> mCurrentSlicedCounter = std::make_shared<DimToValMap>();

    static const size_t kBucketSize = sizeof(CountBucket{});

    bool hitGuardRailLocked(const HashableDimensionKey& newKey);

    FRIEND_TEST(CountMetricProducerTest, TestNonDimensionalEvents);
    FRIEND_TEST(CountMetricProducerTest, TestEventsWithNonSlicedCondition);
    FRIEND_TEST(CountMetricProducerTest, TestEventsWithSlicedCondition);
    FRIEND_TEST(CountMetricProducerTest, TestAnomalyDetection);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COUNT_METRIC_PRODUCER_H
