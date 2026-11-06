package kia.claude;

import java.util.ArrayList;
import java.util.List;

final class Block {
    final List<Line> lines; final List<Unit> units = new ArrayList<>();
    double show = Double.NaN, hide = Double.NaN, first = Double.NaN, last = Double.NaN; boolean timed; int g, k;
    Block(List<Line> lines) { this.lines = lines; for (Line l : lines) units.addAll(l.units); }
}
