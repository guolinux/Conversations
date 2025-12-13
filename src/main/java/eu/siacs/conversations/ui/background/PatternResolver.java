package eu.siacs.conversations.ui.background;

import static eu.siacs.conversations.ui.background.PatternGenerator.PatternType.*;

public class PatternResolver {

    public static final PatternGenerator.PatternType[] PATTERNS = new PatternGenerator.PatternType[] {
            SOFT_GRADIENT,
            RADIAL_GRADIENT,
            SILK_GRADIENT,
            SOFT_MESH,
            CALM_GRID,
            FLOATING_SPHERES,
            ROUNDED_BLOBS,
            FROSTED_NOISE
    };

    public static PatternGenerator.PatternType getTypeFromId(int id) {
        for (int i = 0; i < PATTERNS.length; i++) {
            if (id == ("pattern_" + i).hashCode()) {
                return PATTERNS[i];
            }
        }
        return null;
    }

}