package dev.codex.yugiohmdpro.engine;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/** MDPro3 Percy cdecl API. Card data is 76 bytes, including inline uint16[16]. */
public interface OcgApi extends Library {
    interface ScriptReader extends Callback { Pointer invoke(String name, IntByReference length); }
    interface CardReader extends Callback { int invoke(int code, Pointer data); }
    interface LogHandler extends Callback { int invoke(Pointer duel, int type); }
    void set_script_reader(ScriptReader callback);
    void set_card_reader(CardReader callback);
    void set_message_handler(LogHandler callback);
    Pointer create_duel(int seed);
    void end_duel(Pointer duel);
    void set_player_info(Pointer duel, int player, int lp, int startingHand, int draw);
    void new_card(Pointer duel, int code, byte owner, byte player, byte location, byte sequence, byte position);
    void start_duel(Pointer duel, int options);
    int process(Pointer duel);
    int get_message(Pointer duel, Pointer buffer);
    void get_log_message(Pointer duel, Pointer buffer);
    void set_responsei(Pointer duel, int response);
    void set_responseb(Pointer duel, Pointer response);
    int query_field_count(Pointer duel, byte player, byte location);
    int query_field_info(Pointer duel, Pointer buffer);
    int query_field_card(Pointer duel, byte player, byte location, int flags, Pointer buffer, int useCache);
}
