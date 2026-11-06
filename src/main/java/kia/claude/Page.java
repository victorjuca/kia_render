package kia.claude;

import java.util.ArrayList;
import java.util.List;

final class Page {
    final List<Line> lines = new ArrayList<>();
    List<Unit> units() { List<Unit> r = new ArrayList<>(); for (Line l : lines) r.addAll(l.units); return r; }
}
