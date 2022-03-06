package scot.massie.mc.itemcopy;

import java.util.List;
import java.util.stream.Collectors;

public final class PathSanitiser
{
    private record Substitution(String unsanitised, String sanitised)
    {}

    private PathSanitiser()
    {}

    private static final Substitution[] substitutions =
    {
            new Substitution("§", "§SECTION"),
            new Substitution("/", "§SLASH"),
            new Substitution("\\", "§BACKSLASH"),
            new Substitution(".", "§DOT"),
            new Substitution(":", "§COLON"),
            new Substitution("*", "§ASTERISK"),
            new Substitution("\"", "§QUOTE"),
            new Substitution("|", "§LINE"),
            new Substitution("<", "§LEFTPOINTY"),
            new Substitution(">", "§RIGHTPOINTY"),
            new Substitution("?", "§QUESTION"),
    };

    public static String sanitise(String toBeSanitised)
    {
        String result = toBeSanitised;

        for(Substitution sub : substitutions)
            result = result.replace(sub.unsanitised, sub.sanitised);

        return result;
    }

    public static List<String> sanitise(@SuppressWarnings("TypeMayBeWeakened") List<String> toBeSanitised)
    { return toBeSanitised.stream().map(PathSanitiser::sanitise).collect(Collectors.toList()); }

    public static String desanitise(String toBeDesanitised)
    {
        String result = toBeDesanitised;

        for(int i = substitutions.length - 1; i >= 0; i--)
            result = result.replace(substitutions[i].sanitised, substitutions[i].unsanitised);

        return result;
    }

    public static List<String> desanitise(@SuppressWarnings("TypeMayBeWeakened") List<String> toBeDesanitised)
    { return toBeDesanitised.stream().map(PathSanitiser::desanitise).collect(Collectors.toList()); }
}
