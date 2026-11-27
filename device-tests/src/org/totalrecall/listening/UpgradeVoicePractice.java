package org.totalrecall.listening;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Verify the app can still apply a v0.29 personal correction to the enrolled speaker after update. */
final class UpgradeVoicePractice {
    private static final String SAMPLE = "thread shade and dusk loom";

    private static double[] embedding() {
        double[] sample = new double[128];
        sample[4] = 1;
        return sample;
    }

    private static Method method(Class<?> type, String name, Class<?>... types) throws Exception {
        Method result = type.getDeclaredMethod(name, types);
        result.setAccessible(true);
        return result;
    }

    private static Object open(Class<?> type, Context context) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    static void seed(Context target) throws Exception {
        Class<?> profiles = Class.forName("org.totalrecall.listening.VoiceProfiles");
        Class<?> wording = Class.forName("org.totalrecall.listening.VoiceWording");
        Object change = method(wording, "difference", String.class, String.class)
                .invoke(null, "say thread shade today", "say Threadshade today");
        method(profiles, "enroll", String.class, String.class, String.class, double[].class, wording)
                .invoke(open(profiles, target), null, "Synthetic writer", "Me", embedding(), change);
        if (!target.getSharedPreferences("recall-speech", 0).edit()
                .putString("spellings", "[[\"dusk loom\",\"Duskloom\"]]").commit())
            throw new AssertionError("Could not seed the general spelling");
    }

    static void verify(Context target) throws Exception {
        Class<?> voices = Class.forName("org.totalrecall.listening.VoiceProfiles");
        Object enrolled = method(voices, "list").invoke(open(voices, target));
        Object match = method(voices, "identify", List.class, double[].class).invoke(null, enrolled, embedding());
        Field profile = match.getClass().getDeclaredField("profile");
        profile.setAccessible(true);
        Object speaker = profile.get(match);
        if (speaker == null) throw new AssertionError("Prior saved speaker no longer matches the synthetic sample");

        Class<?> type = Class.forName("org.totalrecall.listening.SpeechContext");
        Object spelling = open(type, target);
        Method apply = method(type, "apply", String.class, String.class, speaker.getClass());
        if (!"Threadshade and Duskloom".equals(apply.invoke(spelling, "", SAMPLE, speaker)))
            throw new AssertionError("Prior voice correction or general spelling lost");
        if (!"thread shade and Duskloom".equals(apply.invoke(spelling, "", SAMPLE, null)))
            throw new AssertionError("Voice-only correction applied to an unidentified speaker");
        method(voices, "reset", Context.class).invoke(null, target);
        if (!target.getSharedPreferences("recall-speech", 0).edit().remove("spellings").commit())
            throw new AssertionError("Could not clean the test spelling");
    }
}
