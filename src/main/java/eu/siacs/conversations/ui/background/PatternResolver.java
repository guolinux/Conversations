package eu.siacs.conversations.ui.background;


import static eu.siacs.conversations.ui.background.PatternGenerator.PatternType.*;
public class PatternResolver {

    private static  final PatternGenerator.PatternType[] PATTERNS = {
            SOFT_MESH,
            GRAINY_PAPER,
            DOTTED_NOISE,
            DIAMOND_GRID,
            FLOATING_SPHERES,
            PERLIN_LIKE_NOISE,
            SOFT_GRADIENT,
            SCRIBBLE_PRO,
            FIBER_TEXTURE
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