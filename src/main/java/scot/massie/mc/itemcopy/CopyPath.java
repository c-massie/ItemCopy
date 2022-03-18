package scot.massie.mc.itemcopy;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public final class CopyPath implements Iterable<String>
{
    public static final CopyPath empty = new CopyPath();
    public static final CopyPath defaultPath = new CopyPath("shareditem");

    private final List<String> steps;

    public CopyPath(@SuppressWarnings("TypeMayBeWeakened") List<String> steps)
    { this.steps = List.copyOf(steps); }

    public CopyPath(String... steps)
    { this(Arrays.asList(steps)); }

    public static CopyPath empty()
    { return empty; }

    public static CopyPath readFromBuf(FriendlyByteBuf buf)
    {
        int length = buf.readInt();
        List<String> steps = new ArrayList<>(length);

        for(int i = 0; i < length; i++)
            steps.add(buf.readUtf());

        return new CopyPath(steps);
    }

    public List<String> getSteps()
    { return steps; }

    public List<String> getStepsSanitised()
    { return PathSanitiser.sanitise(steps); }

    @SuppressWarnings("NullableProblems") // Not adding IDE dependencies.
    @Override
    public Iterator<String> iterator()
    { return steps.iterator(); }

    public int getLength()
    { return steps.size(); }

    public boolean isEmpty()
    { return steps.isEmpty(); }

    public void writeToBuf(FriendlyByteBuf buf)
    {
        buf.writeInt(steps.size());

        for(String step : steps)
            buf.writeUtf(step);
    }
}