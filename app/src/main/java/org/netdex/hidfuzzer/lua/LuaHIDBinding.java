package org.netdex.hidfuzzer.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import org.netdex.hidfuzzer.hid.HIDInterface;
import org.netdex.hidfuzzer.hid.Input;
import org.netdex.hidfuzzer.ltask.AsyncIOBridge;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.luaj.vm2.LuaValue.*;

/**
 * Created by netdex on 12/28/17.
 */

public class LuaHIDBinding {

    private final Globals globals_;
    private final HIDInterface hid_;
    private final AsyncIOBridge aio_;
    private final AtomicBoolean cancelled_;

    private LuaThread mKBLiteRoutine;
    private boolean kbliteStarted = false;

    public LuaHIDBinding(Globals globals_, HIDInterface hid, AsyncIOBridge aio, AtomicBoolean cancelled) {
        this.hid_ = hid;
        this.aio_ = aio;
        this.cancelled_ = cancelled;
        this.globals_ = globals_;

        // put all lua bindings into scope
        LuaFunction[] hidFuncs = {
                new delay(), new test(), new hid_mouse(), new hid_keyboard(), new press_keys(),
                new send_string(), new cancelled(), new log(), new should(), new ask()
        };
        for (LuaFunction f : hidFuncs) {
            globals_.set(f.name(), f);
        }

        // all constant enum values (please tell me if there is a better way to do this)
        LuaTable mouseButtons = tableOf();
        for (Input.M.B imb : Input.M.B.values()) {
            mouseButtons.set(imb.name(), imb.code);
        }
        LuaTable keyCodes = tableOf();
        for (Input.KB.K ikk : Input.KB.K.values()) {
            keyCodes.set(ikk.name(), ikk.c);
        }
        for (Input.KB.M ikm : Input.KB.M.values()) {
            keyCodes.set(ikm.name(), ikm.c);
        }
        globals_.set("ms", mouseButtons);
        globals_.set("kb", keyCodes);

        // table for keyboard light coroutine
        LuaTable kbl = tableOf();
        kbl.set("get", new kblite_get());
        kbl.set("available", new kblite_available());
        kbl.set("begin", new kblite_begin());
//        kbl.set("stop", new kblite_stop());
        globals_.set("kbl", kbl);
    }

    class kblite_begin extends ZeroArgFunction {

        @Override
        public LuaValue call() {
            if (mKBLiteRoutine != null) throw new IllegalStateException("kblite already enabled!");
            mKBLiteRoutine = new LuaThread(globals_, new kblite_coroutine_func(globals_));
            kbliteStarted = true;
            Varargs result = mKBLiteRoutine.resume(NONE);
            return result.arg1();
        }
    }

//    class kblite_stop extends ZeroArgFunction {
//
//        @Override
//        public LuaValue call() {
//            kbliteStarted = false;
//            mKBLiteRoutine = null;
//            hid_.getKeyboardLightListener().kill();
//            return NIL;
//        }
//    }

    class kblite_get extends ZeroArgFunction {

        @Override
        public LuaValue call() {
            if (!kbliteStarted) throw new LuaError("kblite not started!");
            return mKBLiteRoutine.resume(NONE).arg(2);
        }
    }

    class kblite_available extends ZeroArgFunction {

        @Override
        public LuaValue call() {
            int val = hid_.getKeyboardLightListener().available();
            return valueOf(val > 0);
        }
    }

    class kblite_coroutine_func extends ZeroArgFunction {

        private final Globals globals;

        kblite_coroutine_func(Globals globals) {
            this.globals = globals;
        }

        @Override
        public LuaValue call() {
            HIDInterface.KeyboardLightListener kll = hid_.getKeyboardLightListener();
            kll.start();
            globals.yield(NONE);
            while (kll.available() >= 0) {
                int val = kll.read();
                boolean num = (val & 0x1) != 0;
                boolean caps = (val & 0x2) != 0;
                boolean scroll = (val & 0x4) != 0;
                LuaTable result = tableOf();
                result.set("num", valueOf(num));
                result.set("caps", valueOf(caps));
                result.set("scroll", valueOf(scroll));
                globals.yield(varargsOf(new LuaValue[]{result}));
            }
            kbliteStarted = false;
            return NIL;
        }
    }

    class delay extends OneArgFunction {

        @Override
        public LuaValue call(LuaValue arg) {
            long d = arg.checklong();
            hid_.delay(d);
            return NIL;
        }
    }

    class test extends ZeroArgFunction {
        @Override
        public LuaValue call() {
            return valueOf(hid_.test() == 0);
        }
    }

    class hid_mouse extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            byte[] a = new byte[args.narg()];
            for (int i = 1; i <= args.narg(); ++i) {
                a[i - 1] = (byte) args.arg(i).checkint();
            }
            hid_.sendMouse(a);
            return NONE;
        }
    }

    class hid_keyboard extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            byte[] a = new byte[args.narg()];
            for (int i = 1; i <= args.narg(); ++i) {
                a[i - 1] = (byte) args.arg(i).checkint();
            }
            hid_.sendKeyboard(a);
            return NONE;
        }
    }

    class press_keys extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            byte[] a = new byte[args.narg()];
            for (int i = 1; i <= args.narg(); ++i) {
                a[i - 1] = (byte) args.arg(i).checkint();
            }
            hid_.pressKeys(a);
            return NONE;
        }
    }

    class send_string extends TwoArgFunction {

        @Override
        public LuaValue call(LuaValue string, LuaValue delay) {
            String s = string.checkjstring();
            int d = delay.isnil() ? 0 : delay.checkint();
            hid_.sendKeyboard(s, d);
            return NIL;
        }
    }

    class cancelled extends ZeroArgFunction {

        @Override
        public LuaValue call() {
            return valueOf(cancelled_.get());
        }
    }

    class log extends OneArgFunction {

        @Override
        public LuaValue call(LuaValue arg) {
            String msg = arg.tojstring();
            aio_.onLogMessage(msg);
            return NIL;
        }
    }

    class should extends TwoArgFunction {

        @Override
        public LuaValue call(LuaValue title, LuaValue message) {
            String t = title.tojstring();
            String m = message.tojstring();
            return valueOf(aio_.onConfirm(t, m));
        }
    }

    class ask extends TwoArgFunction {

        @Override
        public LuaValue call(LuaValue title, LuaValue defaults) {
            String t = title.tojstring();
            String d = defaults.isnil() ? "" : defaults.tojstring();
            return valueOf(aio_.onPrompt(t, d));
        }
    }
}