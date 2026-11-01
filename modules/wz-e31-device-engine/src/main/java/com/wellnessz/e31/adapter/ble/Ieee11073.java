package com.wellnessz.e31.adapter.ble;

import java.math.BigDecimal;

/**
 * IEEE 11073-20601 numeric types used by the Bluetooth SIG health profiles (little endian):
 * <ul>
 *   <li>SFLOAT – 16 bit: 4-bit signed exponent (high nibble), 12-bit signed mantissa; reserved values NaN 0x07FF,
 *       NRes 0x0800, +INF 0x07FE, −INF 0x0802, reserved 0x0801;</li>
 *   <li>FLOAT – 32 bit: 8-bit signed exponent, 24-bit signed mantissa; NaN 0x007FFFFF, NRes 0x00800000, +INF 0x007FFFFE,
 *       −INF 0x00800002, reserved 0x00800001.</li>
 * </ul>
 * Values are returned as exact decimals ({@code mantissa · 10^exponent}); special values as {@code null}.
 */
public final class Ieee11073 {

    private Ieee11073() {
    }

    /** @param raw the 16-bit value (already assembled little endian) */
    public static BigDecimal sfloat(int raw) {
        int v = raw & 0xFFFF;
        int mantissa = v & 0x0FFF;
        if (mantissa >= 0x07FE && mantissa <= 0x0802) {
            return null; // NaN, NRes, +/-INF, reserved
        }
        int exponent = v >> 12;
        if (exponent >= 0x8) {
            exponent -= 0x10;
        }
        if (mantissa >= 0x0800) {
            mantissa -= 0x1000;
        }
        return BigDecimal.valueOf(mantissa).scaleByPowerOfTen(exponent);
    }

    /** @param raw the 32-bit value (already assembled little endian) */
    public static BigDecimal floatValue(long raw) {
        long v = raw & 0xFFFFFFFFL;
        int mantissa = (int) (v & 0x00FFFFFF);
        if (mantissa >= 0x007FFFFE && mantissa <= 0x00800002) {
            return null;
        }
        int exponent = (int) (v >> 24);
        if (exponent >= 0x80) {
            exponent -= 0x100;
        }
        if (mantissa >= 0x00800000) {
            mantissa -= 0x01000000;
        }
        return BigDecimal.valueOf(mantissa).scaleByPowerOfTen(exponent);
    }

    /** Encodes a decimal as SFLOAT (for simulators and tests); the value must be representable. */
    public static int toSfloat(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        int exponent = -v.scale();
        long mantissa = v.unscaledValue().longValueExact();
        while (Math.abs(mantissa) > 2045 && exponent < 7) {
            if (mantissa % 10 != 0) {
                throw new IllegalArgumentException("Value not representable as SFLOAT");
            }
            mantissa /= 10;
            exponent++;
        }
        while (exponent > 7 && Math.abs(mantissa * 10) <= 2045) {
            mantissa *= 10;
            exponent--;
        }
        if (exponent < -8 || exponent > 7 || Math.abs(mantissa) > 2045) {
            throw new IllegalArgumentException("Value not representable as SFLOAT");
        }
        return ((exponent & 0x0F) << 12) | ((int) mantissa & 0x0FFF);
    }

    /** Encodes a decimal as FLOAT. */
    public static long toFloat(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        int exponent = -v.scale();
        long mantissa = v.unscaledValue().longValueExact();
        if (exponent < -128 || exponent > 127 || Math.abs(mantissa) > 0x007FFFFD) {
            throw new IllegalArgumentException("Value not representable as FLOAT");
        }
        return ((long) (exponent & 0xFF) << 24) | (mantissa & 0x00FFFFFFL);
    }
}
