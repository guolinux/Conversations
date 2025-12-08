package eu.siacs.conversations.ui.background;

import static eu.siacs.conversations.ui.background.PatternGenerator.PatternType.*;

public class PatternResolver {

    public static final PatternGenerator.PatternType[] PATTERNS = new PatternGenerator.PatternType[] {
            SOFT_GRADIENT,
            RADIAL_GRADIENT,
            SOFT_MESH,
            GRAINY_PAPER,
            FLOATING_SPHERES,
            SCRIBBLE_PRO,
            PLASMA
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