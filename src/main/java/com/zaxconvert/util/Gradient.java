package com.zaxconvert.util;

import net.md_5.bungee.api.ChatColor;

import java.awt.Color;

/** Gradient chat helpers for the [Z-Converting] messages. */
public final class Gradient {

    public static final String PREFIX_FROM = "#7F5AF0";
    public static final String PREFIX_TO = "#00D9FF";

    private Gradient() {
    }

    /** Colors every character of the text from one hex color to another. */
    public static String text(String text, String from, String to, boolean bold) {
        Color a = Color.decode(from);
        Color b = Color.decode(to);
        StringBuilder sb = new StringBuilder();
        int n = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                sb.append(ch);
                continue;
            }
            double t = (double) i / n;
            Color c = new Color(
                    (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                    (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
            sb.append(ChatColor.of(c));
            if (bold) sb.append(ChatColor.BOLD);
            sb.append(ch);
        }
        return sb.toString();
    }

    public static String prefix() {
        return text("[Z-Converting]", PREFIX_FROM, PREFIX_TO, true) + ChatColor.RESET + " ";
    }

    public static String info(String msg) {
        return prefix() + text(msg, "#B8C0FF", "#E7ECFF", false);
    }

    public static String success(String msg) {
        return prefix() + text(msg, "#00F5A0", "#00D9F5", false);
    }

    public static String warn(String msg) {
        return prefix() + text(msg, "#F7971E", "#FFD200", false);
    }

    public static String error(String msg) {
        return prefix() + text(msg, "#FF416C", "#FF4B2B", false);
    }

    public static String title(String msg) {
        return prefix() + text(msg, "#FF6FD8", "#3813C2", true);
    }
}
