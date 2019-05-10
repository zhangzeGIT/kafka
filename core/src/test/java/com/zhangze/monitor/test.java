package com.zhangze.monitor;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.reporting.ConsoleReporter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by zhangze on 2019/5/10
 */
public class test {
    public static void main(String[] args) throws Exception{
        final List<String> list = new ArrayList<>();
        ConsoleReporter.enable(5, TimeUnit.SECONDS);
        Gauge<Integer> g = Metrics.newGauge(test.class, "testGauge", new Gauge<Integer>() {
            @Override
            public Integer value() {
                return list.size();
            }
        });
        while (true) {
            list.add("s");
            Thread.sleep(1000);
        }
    }
}
