package app.driftremote.core;

/** Android key codes the TV understands (same numbers as android.view.KeyEvent). */
public final class Keys {
    private Keys() {}

    public static final int HOME = 3, BACK = 4;
    public static final int DPAD_UP = 19, DPAD_DOWN = 20, DPAD_LEFT = 21, DPAD_RIGHT = 22, DPAD_CENTER = 23;
    public static final int VOLUME_UP = 24, VOLUME_DOWN = 25, POWER = 26;
    public static final int SPACE = 62, ENTER = 66, DEL = 67;
    public static final int MENU = 82, SEARCH = 84;
    public static final int MEDIA_PLAY_PAUSE = 85, MEDIA_NEXT = 87, MEDIA_PREVIOUS = 88;
    public static final int MEDIA_REWIND = 89, MEDIA_FAST_FORWARD = 90;
    public static final int VOLUME_MUTE = 164, INFO = 165, CHANNEL_UP = 166, CHANNEL_DOWN = 167;
    public static final int GUIDE = 172, CAPTIONS = 175, SETTINGS = 176, TV_INPUT = 178;
    public static final int PROG_RED = 183, PROG_GREEN = 184, PROG_YELLOW = 185, PROG_BLUE = 186;
    public static final int APP_SWITCH = 187, ALL_APPS = 284;

    /** Key code for typing one character with key presses, or 0 if there is none. */
    public static int forChar(char c) {
        if (c >= 'a' && c <= 'z') return 29 + (c - 'a');
        if (c >= 'A' && c <= 'Z') return 29 + (c - 'A');
        if (c >= '0' && c <= '9') return 7 + (c - '0');
        switch (c) {
            case ' ': return SPACE;
            case '\n': return ENTER;
            case ',': return 55;
            case '.': return 56;
            case '-': return 69;
            case '=': return 70;
            case '/': return 76;
            case '@': return 77;
            case '+': return 81;
            case '*': return 17;
            case '#': return 18;
            case ';': return 74;
            case '\'': return 75;
            default: return 0;
        }
    }
}
