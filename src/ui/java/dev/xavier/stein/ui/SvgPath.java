/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/**
 * Parser of the SVG path syntax ({@code d="M6 9l6 6 6-6"}: M L H V C S A Z, upper and lower case)
 * into a Java2D shape, for the stroke icons of {@link Shapes}.
 */
final class SvgPath {

    private final String d;

    /** Where the parser is in {@link #d}. */
    private int pos;

    private SvgPath(String d) {
        this.d = d;
    }

    /** The path of an SVG ({@code d="..."}) as a Java2D shape. */
    static Path2D.Double parse(String d) {
        return new SvgPath(d).path();
    }

    private Path2D.Double path() {
        Path2D.Double p = new Path2D.Double();
        char cmd = 'M';
        double x = 0, y = 0; // where the pen is
        double sx = 0, sy = 0; // start of the segment (for Z)
        double qx = 0, qy = 0; // the last control point (for S)
        boolean curve = false;
        int n = d.length();
        while (true) {
            while (pos < n && (d.charAt(pos) == ' ' || d.charAt(pos) == ',')) {
                pos++;
            }
            if (pos >= n) {
                break;
            }
            char ch = d.charAt(pos);
            if (Character.isLetter(ch)) {
                cmd = ch;
                pos++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.closePath();
                    x = sx;
                    y = sy;
                    curve = false;
                    continue;
                }
            }
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = ox + num();
                    y = oy + num();
                    p.moveTo(x, y);
                    sx = x;
                    sy = y;
                    cmd = rel ? 'l' : 'L'; // the following pairs are lines
                    curve = false;
                }
                case 'L' -> {
                    x = ox + num();
                    y = oy + num();
                    p.lineTo(x, y);
                    curve = false;
                }
                case 'H' -> {
                    x = ox + num();
                    p.lineTo(x, y);
                    curve = false;
                }
                case 'V' -> {
                    y = oy + num();
                    p.lineTo(x, y);
                    curve = false;
                }
                case 'C', 'S' -> {
                    double x1, y1;
                    if (Character.toUpperCase(cmd) == 'C') {
                        x1 = ox + num();
                        y1 = oy + num();
                    } else {
                        // The first control point is the reflection of the previous curve's last
                        // one.
                        x1 = curve ? 2 * x - qx : x;
                        y1 = curve ? 2 * y - qy : y;
                    }
                    double x2 = ox + num(), y2 = oy + num();
                    double ex = ox + num(), ey = oy + num();
                    p.curveTo(x1, y1, x2, y2, ex, ey);
                    qx = x2;
                    qy = y2;
                    x = ex;
                    y = ey;
                    curve = true;
                }
                case 'A' -> {
                    double rx = num(), ry = num(), rot = num();
                    boolean large = num() != 0, sweep = num() != 0;
                    double ex = ox + num(), ey = oy + num();
                    arcTo(p, x, y, rx, ry, rot, large, sweep, ex, ey);
                    x = ex;
                    y = ey;
                    curve = false;
                }
                default ->
                        throw new IllegalArgumentException(
                                "unknown path command: " + cmd + " in " + d);
            }
        }
        return p;
    }

    /** The path's next number: "13-13" is two, and ".5.5" too. */
    private double num() {
        int n = d.length();
        int i = pos;
        while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',')) {
            i++;
        }
        int from = i;
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
            i++;
        }
        boolean dot = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c == '.' && !dot) {
                dot = true;
            } else if (c < '0' || c > '9') {
                break;
            }
            i++;
        }
        pos = i;
        return Double.parseDouble(d.substring(from, i));
    }

    /** The SVG elliptical arc (endpoint to endpoint) as a Java2D arc (by center). */
    private static void arcTo(
            Path2D.Double p,
            double x0,
            double y0,
            double rx,
            double ry,
            double rot,
            boolean large,
            boolean sweep,
            double x,
            double y) {
        if (rx == 0 || ry == 0 || x0 == x && y0 == y) {
            p.lineTo(x, y);
            return;
        }
        double a = Math.toRadians(rot % 360.0);
        double cos = Math.cos(a), sin = Math.sin(a);
        double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
        double x1 = cos * dx2 + sin * dy2, y1 = -sin * dx2 + cos * dy2;
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double prx = rx * rx, pry = ry * ry, px1 = x1 * x1, py1 = y1 * y1;
        double check = px1 / prx + py1 / pry;
        if (check > 1) { // radius too short to reach both points: grows until it does
            rx *= Math.sqrt(check);
            ry *= Math.sqrt(check);
            prx = rx * rx;
            pry = ry * ry;
        }
        double sq = Math.max(0, (prx * pry - prx * py1 - pry * px1) / (prx * py1 + pry * px1));
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(sq);
        double cx1 = coef * (rx * y1 / ry), cy1 = coef * -(ry * x1 / rx);
        double cx = (x0 + x) / 2.0 + (cos * cx1 - sin * cy1),
                cy = (y0 + y) / 2.0 + (sin * cx1 + cos * cy1);
        double ux = (x1 - cx1) / rx,
                uy = (y1 - cy1) / ry,
                vx = (-x1 - cx1) / rx,
                vy = (-y1 - cy1) / ry;
        double start =
                Math.toDegrees(
                        (uy < 0 ? -1 : 1) * Math.acos(clamp(ux / Math.sqrt(ux * ux + uy * uy))));
        double extent =
                Math.toDegrees(
                        (ux * vy - uy * vx < 0 ? -1 : 1)
                                * Math.acos(
                                        clamp(
                                                (ux * vx + uy * vy)
                                                        / Math.sqrt(
                                                                (ux * ux + uy * uy)
                                                                        * (vx * vx + vy * vy)))));
        if (!sweep && extent > 0) {
            extent -= 360.0;
        } else if (sweep && extent < 0) {
            extent += 360.0;
        }
        Arc2D.Double arc =
                new Arc2D.Double(cx - rx, cy - ry, rx * 2, ry * 2, -start, -extent, Arc2D.OPEN);
        p.append(AffineTransform.getRotateInstance(a, cx, cy).createTransformedShape(arc), true);
    }

    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
