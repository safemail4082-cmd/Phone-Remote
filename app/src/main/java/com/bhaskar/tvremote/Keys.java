package com.bhaskar.tvremote;

/** Android key codes understood by the TV (values from the remote protocol). */
final class Keys {
    static final int HOME = 3, BACK = 4;
    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, CENTER = 23;
    static final int VOL_UP = 24, VOL_DOWN = 25, POWER = 26;
    static final int MENU = 82, SEARCH = 84;
    static final int PLAY_PAUSE = 85, NEXT = 87, PREV = 88, REWIND = 89, FORWARD = 90;
    static final int MUTE = 164, INFO = 165, CH_UP = 166, CH_DOWN = 167;
    static final int SETTINGS = 176, TV_INPUT = 178;
    static final int DEL = 67, ENTER = 66, SPACE = 62;

    /** keycode for a typed character, or -1 when it can't be typed with a plain key */
    static int forChar(char c) {
        if (c >= 'A' && c <= 'Z') c = Character.toLowerCase(c);
        if (c >= 'a' && c <= 'z') return 29 + (c - 'a');
        if (c >= '0' && c <= '9') return 7 + (c - '0');
        switch (c) {
            case ' ': return SPACE;
            case '.': return 56;
            case ',': return 55;
            case '-': return 69;
            case '/': return 76;
            case '@': return 77;
            case '\'': return 75;
            case ';': return 74;
            case '=': return 70;
            case '\n': return ENTER;
            default: return -1;
        }
    }
}
