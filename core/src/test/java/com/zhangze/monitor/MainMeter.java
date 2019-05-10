package com.zhangze.monitor;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.reporting.ConsoleReporter;

import java.util.concurrent.TimeUnit;

/**
 * Created by zhangze on 2019/5/10
 * 某时间段内平均请求数
 */
public class MainMeter {
    private static Meter meter = Metrics.newMeter(MainMeter.class, "Meter",
            "requests", TimeUnit.SECONDS);

    public static void main(String[] args) throws InterruptedException{
        ConsoleReporter.enable(1, TimeUnit.SECONDS);
        while(true) {
            // 调用一次方法都认为受到一次请求
            meter.mark();
            meter.mark();
            Thread.sleep(1000);
        }
    }
}
