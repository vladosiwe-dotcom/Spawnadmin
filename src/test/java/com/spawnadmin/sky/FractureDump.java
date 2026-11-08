package com.spawnadmin.sky;

import com.spawnadmin.sky.geom.CrackSegment;
import com.spawnadmin.sky.geom.Fracture;
import com.spawnadmin.sky.geom.ShardData;
import com.spawnadmin.sky.geom.V2;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Writes build/fracture-dump.txt for visual inspection with tools/plot_fracture.py. */
class FractureDump {
    @Test
    void dump() throws IOException {
        Fracture f = Fracture.generate(20261108L);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "I %f %f %f %f%n", f.impact.x(), f.impact.y(), f.openRadius, f.maxFront));
        for (ShardData s : f.shards) {
            sb.append(String.format(Locale.ROOT, "S %d %d %d", s.index, s.detaches ? 1 : 0, s.hovers ? 1 : 0));
            for (V2 p : s.outline) sb.append(String.format(Locale.ROOT, " %f %f", p.x(), p.y()));
            sb.append('\n');
        }
        for (CrackSegment c : f.cracks) {
            sb.append(String.format(Locale.ROOT, "C %d %f %f %f %f %f %f %f %f%n", c.hairline() ? 1 : 0,
                    c.a().x(), c.a().y(), c.b().x(), c.b().y(), c.da(), c.db(), c.weight(), c.width()));
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/fracture-dump.txt"), sb.toString());
    }
}
