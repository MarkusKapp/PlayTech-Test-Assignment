package main;

import java.io.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        long initialBudget = Long.parseLong(args[0]);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintStream log = System.err;

        BudgetTracker budget = new BudgetTracker(initialBudget);
        BotStrategy strategy = new BotStrategy();

        System.out.println(BotStrategy.CATEGORY);

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("S ")) {
                strategy.onSummary(line, budget);
            } else if (line.startsWith("W") || line.startsWith("L")) {
                handleResult(line, budget, log);
            } else if (line.contains("video.category")) {
                Impression imp = ImpressionParser.parse(line);
                long[] bids = strategy.calculateBid(imp, budget);
                System.out.println(bids[0] + " " + bids[1]);
            }
        }
    }

    private static void handleResult(String line, BudgetTracker budget, PrintStream log) {
        if (line.startsWith("W ")) {
            long cost = Long.parseLong(line.substring(2).trim());
            budget.recordWin(cost);
        }
    }
}

class Impression {
    String videoCategory, viewerAge, viewerGender;
    long viewCount, commentCount;
    boolean subscribedViewer;
    String[] viewerInterests;

    @Override
    public String toString() {
        return String.format("Impression{cat=%s, views=%d, sub=%b}", videoCategory, viewCount, subscribedViewer);
    }
}

class ImpressionParser {
    static Impression parse(String line) {
        Impression imp = new Impression();
        for (String part : line.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length < 2) continue;
            String key = kv[0].trim(), val = kv[1].trim();

            switch (key) {
                case "video.category":     imp.videoCategory = val; break;
                case "video.viewCount":    imp.viewCount = Long.parseLong(val); break;
                case "video.commentCount": imp.commentCount = Long.parseLong(val); break;
                case "viewer.subscribed":  imp.subscribedViewer = "Y".equals(val); break;
                case "viewer.age":         imp.viewerAge = val; break;
                case "viewer.gender":      imp.viewerGender = val; break;
                case "viewer.interests":   imp.viewerInterests = val.split(";"); break;
            }
        }
        return imp;
    }
}

class BudgetTracker {
    private static final double FLOOR_PERCENT = 0.30;
    private final long initial;
    private long totalSpent;

    BudgetTracker(long initial) { this.initial = initial; }

    void recordWin(long cost) { totalSpent += cost; }
    long remaining() { return initial - totalSpent; }
    double spentFraction() { return (double) totalSpent / initial; }
    boolean floorCleared() { return spentFraction() >= FLOOR_PERCENT; }

    long mustStillSpend() {
        return Math.max(0, (long) (initial * FLOOR_PERCENT) - totalSpent);
    }
}

class BotStrategy {
    static final String CATEGORY = "Video Games";
    private int summaryCount = 0;
    private double aggression = 1.1;

    double scoreImpression(Impression imp) {
        double score = 0;

        if (imp.subscribedViewer) score += 0.20;
        if (CATEGORY.equalsIgnoreCase(imp.videoCategory)) score += 0.25;

        if (imp.viewerInterests != null) {
            for (int i = 0; i < imp.viewerInterests.length; i++) {
                if (CATEGORY.equalsIgnoreCase(imp.viewerInterests[i].trim())) {
                    score += (i == 0) ? 0.30 : (i == 1) ? 0.15 : 0.07;
                    break;
                }
            }
        }

        if (imp.viewCount > 0) {
            score += Math.min((double) imp.commentCount / imp.viewCount / 0.01, 1.0) * 0.15;
        }

        score += switchAge(imp.viewerAge);
        if ("M".equals(imp.viewerGender)) score += 0.07;
        else if ("F".equals(imp.viewerGender)) score += 0.02;

        long v = imp.viewCount;
        if (v >= 50_000_000) score += 0.04;
        else if (v >= 5_000_000) score += 0.08;
        else if (v >= 500_000) score += 0.06;
        else if (v < 50_000) score += 0.02;

        return Math.min(Math.max(score, 0.0), 1.0);
    }

    private double switchAge(String age) {
        if (age == null) return 0;
        return switch (age) {
            case "13-17" -> 0.12;
            case "18-24" -> 0.10;
            case "25-34" -> 0.07;
            case "35-44" -> 0.02;
            default -> 0;
        };
    }

    long[] calculateBid(Impression imp, BudgetTracker budget) {
        if (budget.remaining() <= 0) return new long[]{0, 0};

        double score = scoreImpression(imp);
        boolean cleared = budget.floorCleared();

        if (cleared) {
            if (score < 0.01) return new long[]{1, 1};
            long max = Math.min(budget.remaining(), Math.max(1, (long) (score * score * 100)));
            return new long[]{(long) (max * 0.4), max};
        }

        long stillNeed = budget.mustStillSpend();
        boolean panic = stillNeed > budget.remaining() * 0.30;

        if (score < 0.01 && !panic) return new long[]{1, 1};

        long max = (long) (Math.pow(score, 2) * 75 * aggression);
        max = Math.min(budget.remaining(), Math.max(1, max));

        if (stillNeed > 0) max = Math.min(max, stillNeed + 3);

        long start = (long) (max * (panic ? 0.6 : 0.5));
        if (panic) {
            double urgency = Math.min((double) stillNeed / budget.remaining(), 1.0);
            max = Math.min(budget.remaining(), (long) (max * (1.0 + urgency * 0.8)));
        }

        return new long[]{Math.max(1, Math.min(start, max)), max};
    }

    void onSummary(String line, BudgetTracker budget) {
        String[] parts = line.split("\\s+");
        if (parts.length < 3) return;

        summaryCount++;

        if (budget.floorCleared()) {
            return;
        }

        double target = 0.35 * (1.0 - Math.exp(-summaryCount / 4000.0));
        double ratio = target > 0 ? budget.spentFraction() / target : 1.0;

        // Adjust aggression
        if (ratio < 0.70) aggression *= 1.30;
        else if (ratio < 0.85) aggression *= 1.12;
        else if (ratio < 0.90) aggression *= 1.06;
        else if (ratio > 1.30) aggression *= 0.80;
        else if (ratio > 1.10) aggression *= 0.92;
        aggression = Math.max(0.4, Math.min(4.0, aggression));
    }
}
