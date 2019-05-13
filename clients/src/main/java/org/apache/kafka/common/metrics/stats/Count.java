/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.common.metrics.stats;

import java.util.List;

import org.apache.kafka.common.metrics.MetricConfig;

/**
 * A {@link SampledStat} that maintains a simple count of what it has seen.
 */
// count和total不同之处在于，随着时间窗口的推移，count中会有sample过期，所以count记录的是一段时间内的总数
// 而total的值则是单调递增的
public class Count extends SampledStat {

    public Count() {
        super(0);
    }

    // 完成sample的value 的累加
    @Override
    protected void update(Sample sample, MetricConfig config, double value, long now) {
        sample.value += 1.0;
    }

    // 将所有未过期的sample的value求和并返回
    @Override
    public double combine(List<Sample> samples, MetricConfig config, long now) {
        double total = 0.0;
        for (int i = 0; i < samples.size(); i++)
            total += samples.get(i).value;
        return total;
    }

}
