package com.zhangze.monitor;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;
import com.yammer.metrics.reporting.ConsoleReporter;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Created by zhangze on 2019/5/10
 */
public class MainTimer {
    private static Timer timer = Metrics.newTimer(MainTimer.class,"tstTimer",
            TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

    public static void main(String[] args) throws Exception{
        ConsoleReporter.enable(1, TimeUnit.SECONDS);
        Random rn = new Random();
        timer.time();
        System.out.println();
        while(true) {
            TimerContext context = timer.time();
            Thread.sleep(rn.nextInt(1000));
            context.stop();
        }
    }
}
