package com.zhangze.monitor;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.reporting.ConsoleReporter;

import java.util.concurrent.TimeUnit;

/**
 * Created by zhangze on 2019/5/10
 */
public class MainHistogram {
    private static Histogram histogram = Metrics.newHistogram(MainHistogram.class,"testHistoga");

    public static void main(String[] args) throws Exception{
        ConsoleReporter.enable(1, TimeUnit.SECONDS);
        int i = 0;
        while(true) {
            histogram.update(i++);
            Thread.sleep(1000);
        }
    }
}
