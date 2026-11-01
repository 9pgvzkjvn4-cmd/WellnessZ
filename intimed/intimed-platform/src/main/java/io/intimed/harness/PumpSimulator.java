package io.intimed.harness;

import io.intimed.engine.parse.DelimitedRecords;
import io.intimed.engine.transport.Mllp;

/** Infusion pump simulator for the bidirectional check (XT-12): accepts an RGV^O15 programming request, validates it and
 *  answers RRG^O16 – the pump then reports the programmed rate in its next IPEC observation. */
public final class PumpSimulator {
    private PumpSimulator() { }

    public static String program(String rgv) {
        DelimitedRecords r = new DelimitedRecords(rgv, true);
        String type = r.first("MSH-9.1") + "^" + r.first("MSH-9.2");
        String rate = r.first("RXG-8");
        boolean ok = "RGV^O15".equals(type) && rate != null && rate.matches("\\d+(\\.\\d+)?") && Double.parseDouble(rate) <= 999;
        return Mllp.ack(rgv, ok ? "AA" : "AR", ok ? "programmed" : "programming rejected").replace("ACK^R01^ACK", "RRG^O16^RRG_O16");
    }

    public static String programmedRate(String rgv) { return new DelimitedRecords(rgv, true).first("RXG-8"); }
}
