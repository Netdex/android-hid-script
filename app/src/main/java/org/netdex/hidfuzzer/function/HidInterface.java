package org.netdex.hidfuzzer.function;

import java.io.IOException;
import java.util.Arrays;

import org.netdex.hidfuzzer.util.Command;

import eu.chainfire.libsuperuser.Shell;

/**
 * Wrapper for HID class for ease of usage
 * <p>
 * Created by netdex on 1/16/2017.
 */

public class HidInterface {
    private final Shell.Threaded su_;
    private final String devicePath_;


    public HidInterface(Shell.Threaded su, String devicePath) {
        this.su_ = su;
        this.devicePath_ = devicePath;
    }

    /**
     * Tests if current HID device is connected by sending a dummy key
     */
    public boolean test() throws Shell.ShellDiedException {
        try {
            sendKeyboard((byte) 0, Input.KB.K.VOLUME_UP.c);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Sends mouse command
     *
     * @param offset command byte[] to send, defined in HID.java
     */
    public void sendMouse(byte... offset) {
        sendHIDMouse(su_, devicePath_, offset);
    }

    /**
     * Sends keyboard command
     *
     * @param keys command byte[] to send, defined in HID.java
     */
    public void sendKeyboard(byte... keys) throws Shell.ShellDiedException, IOException {
        sendHIDKeyboard(su_, devicePath_, keys);
    }

    /**
     * Presses keys and releases it
     *
     * @param keys command byte[] to send, defined in HID.java
     */
    public void pressKeys(byte... keys) throws Shell.ShellDiedException, IOException {
        sendKeyboard(keys);
        sendKeyboard();
    }

    /* Begin string to code conversion tables */
    private static final String MP_ALPHA = "abcdefghijklmnopqrstuvwxyz";        // 0x04
    private static final String MP_ALPHA_ALT = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";    // 0x04 SHIFT
    private static final String MP_NUM = "1234567890";                          // 0x1E
    private static final String MP_NUM_ALT = "!@#$%^&*()";                      // 0x1E SHIFT
    private static final String MP_SPEC = " -=[]\\#;'`,./";                     // 0x2C
    private static final String MP_SPEC_ALT = " _+{}| :\"~<>?";                 // 0x2C SHIFT
    private static final String MP_SU_SPEC = "\n";                              // 0X28

    private static final String[] AP_ATT = {MP_ALPHA, MP_ALPHA_ALT, MP_NUM, MP_NUM_ALT, MP_SPEC, MP_SPEC_ALT, MP_SU_SPEC};
    private static final boolean[] AP_SHIFT = {false, true, false, true, false, true, false};
    private static final byte[] AP_OFFSET = {0x04, 0x04, 0x1E, 0x1E, 0x2C, 0x2C, 0x28};

    private static final byte[] AP_MAP_CODE = new byte[128];
    private static final boolean[] AP_MAP_SHIFT = new boolean[128];

    // build fast conversion tables from human readable data
    static {
        for (int i = 0; i < 128; i++) {
            char c = (char) i;
            boolean shift = false;
            byte code = 0;

            int idx = 0;
            while (idx < AP_ATT.length) {
                int tc;
                if ((tc = AP_ATT[idx].indexOf(c)) != -1) {
                    code = (byte) (AP_OFFSET[idx] + tc);
                    shift = AP_SHIFT[idx];
                    break;
                }
                idx++;
            }
            if (idx == AP_ATT.length) {
                AP_MAP_CODE[i] = -1;
            } else {
                AP_MAP_CODE[i] = code;
                AP_MAP_SHIFT[i] = shift;
            }
        }
    }
    /* End string to code conversion tables */

    /**
     * Sends a string to the keyboard with no delay
     *
     * @param s String to send
     */
    public void sendKeyboard(String s) throws Shell.ShellDiedException, IOException, InterruptedException {
        sendKeyboard(s, 0);
    }

    /**
     * Sends a string to the keyboard
     *
     * @param s String to send
     * @param d Delay after key press
     */
    public void sendKeyboard(String s, int d) throws Shell.ShellDiedException, IOException, InterruptedException {
        char lc = Character.MIN_VALUE;
        for (char c : s.toCharArray()) {
            byte cd = AP_MAP_CODE[(int) c];
            boolean st = AP_MAP_SHIFT[(int) c];
            if (cd == -1)
                throw new IllegalArgumentException("Given string contains illegal characters");
            if (Character.toLowerCase(c) == Character.toLowerCase(lc)) sendKeyboard();
            sendKeyboard(st ? Input.KB.M.LSHIFT.c : 0, cd);
            if (d != 0){
                Thread.sleep(d);
            }
            lc = c;
        }
        sendKeyboard();
    }

    /**
     * A        B        C        D
     * XXXXXXXX XXXXXXXX XXXXXXXX XXXXXXXX
     * <p>
     * A: Mouse button mask
     * B: Mouse X-offset
     * C: Mouse Y-offset
     * D: Mouse wheel offset
     *
     * @param sh     SUExtensions shell
     * @param dev    Mouse device (/dev/hidg1)
     * @param offset HID mouse bytes
     */
    public static void sendHIDMouse(Shell.Threaded sh, String dev, byte... offset) {
        byte[] buffer = new byte[4];
        throw new UnsupportedOperationException("mouse descriptor not implemented"); // TODO
        /*
        if (offset.length > 4)
            throw new IllegalArgumentException("Your mouse can only move in two dimensions");
        Arrays.fill(mouse_buf, (byte) 0);
        System.arraycopy(offset, 0, mouse_buf, 0, offset.length);
        return write_bytes(sh, dev, mouse_buf);*/
    }

    /**
     * A        B        C        D        E        F        G        H
     * XXXXXXXX 00000000 XXXXXXXX XXXXXXXX XXXXXXXX XXXXXXXX XXXXXXXX XXXXXXXX
     * <p>
     * A: K modifier mask
     * B: Reserved
     * C: K 1; D: K 2; E: K 3; F: K 4; G: K 5; H: K 6;
     *
     * @param sh   SUExtensions shell
     * @param dev  KB device (/dev/hidg0)
     * @param keys HID keyboard bytes
     */
    public static void sendHIDKeyboard(Shell.Threaded sh, String dev, byte... keys) throws Shell.ShellDiedException, IOException {
        byte[] buffer = new byte[8];
        if (keys.length > 7)
            throw new IllegalArgumentException("Cannot send more than 6 keys");
        Arrays.fill(buffer, (byte) 0);
        if (keys.length > 0) buffer[0] = keys[0];
        if (keys.length > 1) System.arraycopy(keys, 1, buffer, 2, keys.length - 1);
        write_bytes(sh, dev, buffer);
    }

    /**
     * Writes bytes to a file with "echo -n -e [binary string] > file"
     *
     * @param sh  Threaded shell to send echo command
     * @param dev File to write to
     * @param arr Bytes to write
     */
    private static void write_bytes(Shell.Threaded sh, String dev, byte[] arr) throws Shell.ShellDiedException, IOException {
        int exitCode = sh.run(Command.echoToFile(Command.escapeBytes(arr), dev, true, false));
        if (exitCode != 0)
            throw new IOException(String.format("Could not write to device \"%s\"", dev));
    }

}