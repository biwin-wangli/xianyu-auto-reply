package com.xianyu.autoreply.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BrowserTrajectoryUtils {

    public static class TrajectoryPoint {
        public double x;
        public double y;
        public double delay;

        public TrajectoryPoint(double x, double y, double delay) {
            this.x = x;
            this.y = y;
            this.delay = delay;
        }
    }

    /**
     * Port of _generate_physics_trajectory from Python
     * Based on physics acceleration model - Fast Mode
     */
    public static List<TrajectoryPoint> generatePhysicsTrajectory(double distance) {
        Random random = new Random();
        List<TrajectoryPoint> trajectory = new ArrayList<>();
        
        // Ensure overshoot 100-110%
        double targetDistance = distance * (2.0 + random.nextDouble() * 0.1);
        
        // Minimal steps (5-8 steps)
        int steps = 5 + random.nextInt(4); // 5 to 8
        
        // Fast time interval (0.2ms - 0.5ms) - roughly converted to use in sleep
        double baseDelay = 0.0002 + random.nextDouble() * 0.0003;
        
        for (int i = 0; i < steps; i++) {
            double progress = (double)(i + 1) / steps;
            
            // Calculate current position (Square acceleration curve)
            double x = targetDistance * Math.pow(progress, 1.5);
            
            // Minimal Y jitter
            double y = random.nextDouble() * 2;
            
            // Short delay
            double delay = baseDelay * (0.9 + random.nextDouble() * 0.2); // 0.9 to 1.1 factor
            
            trajectory.add(new TrajectoryPoint(x, y, delay));
        }
        
        return trajectory;
    }
}
