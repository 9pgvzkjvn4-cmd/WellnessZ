package com.wellnessz.gateway.web;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import com.wellnessz.gateway.connector.ConnectorStatus;
import com.wellnessz.gateway.queue.QueueStats;

/**
 * Local status page (localhost only): identity, connectivity, queue, connectors and hardening findings. It shows
 * counts, sequence numbers and states – never message content, employee data or secrets.
 */
@RestController
public class StatusController {

    private final GatewayStatus status;

    public StatusController(GatewayStatus status) {
        this.status = status;
    }

    @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public GatewayStatus.Snapshot statusJson() {
        return status.snapshot();
    }

    @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        GatewayStatus.Snapshot s = status.snapshot();
        QueueStats q = s.queue();
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"en-GB\"><head><meta charset=\"utf-8\">")
                .append("<meta http-equiv=\"refresh\" content=\"10\"><title>WellnessZ device gateway</title>")
                .append("<style>body{font-family:system-ui,sans-serif;margin:24px;color:#1b1f24;background:#fff}")
                .append("table{border-collapse:collapse;margin:8px 0 20px}td,th{border:1px solid #d0d7de;")
                .append("padding:4px 10px;text-align:left}th{background:#f6f8fa}.ok{color:#1a7f37}.bad{color:#cf222e}")
                .append("</style></head><body><h1>WellnessZ device gateway</h1>");
        html.append("<h2>Identity and connectivity</h2><table>");
        row(html, "Role", s.role());
        row(html, "Endpoint", s.endpointId() == null ? "not enrolled" : s.endpointId());
        row(html, "Ready", Boolean.toString(s.ready()));
        row(html, "Configuration version", s.configVersion());
        rowClass(html, "Core", s.coreOnline() ? "online" : "offline", s.coreOnline());
        row(html, "Last delivery", str(s.lastForwardAt()));
        row(html, "Last delivery error", s.lastForwardError());
        row(html, "Last heartbeat", str(s.lastHeartbeatAt()));
        row(html, "Last heartbeat error", s.lastHeartbeatError());
        row(html, "Clock offset (ms)", Long.toString(s.clockOffsetMillis()));
        html.append("</table><h2>Store-and-forward queue</h2><table>");
        row(html, "Depth", Integer.toString(q.depth()));
        row(html, "Oldest item captured", str(q.oldestCapturedAt()));
        row(html, "Size (bytes)", Long.toString(q.bytes()));
        rowClass(html, "Capacity used (%)", Double.toString(q.capacityPct()), q.capacityPct() < 70);
        row(html, "Next sequence", Long.toString(q.nextSequence()));
        row(html, "Dead letters", Long.toString(q.deadLetters()));
        rowClass(html, "Quarantined", Boolean.toString(q.quarantined()), !q.quarantined());
        html.append("</table><h2>Connectors</h2><table><tr><th>Id</th><th>Option</th><th>Device instance</th>")
                .append("<th>State</th><th>Last seen</th><th>Received</th><th>Errors</th><th>Detail</th></tr>");
        for (ConnectorStatus c : s.connectors()) {
            html.append("<tr>");
            cell(html, c.id());
            cell(html, c.type());
            cell(html, c.deviceInstanceId());
            cell(html, c.state().name());
            cell(html, str(c.lastSeenAt()));
            cell(html, Long.toString(c.received()));
            cell(html, Long.toString(c.errors()));
            cell(html, c.detail());
            html.append("</tr>");
        }
        html.append("</table><h2>Hardening self-check</h2><table>");
        for (Map.Entry<String, Object> e : s.hardening().entrySet()) {
            row(html, e.getKey(), String.valueOf(e.getValue()));
        }
        html.append("</table><p>JSON: <a href=\"/status\">/status</a> · health: ")
                .append("<a href=\"/actuator/health\">/actuator/health</a> · metrics: ")
                .append("<a href=\"/actuator/prometheus\">/actuator/prometheus</a></p></body></html>");
        return html.toString();
    }

    private static String str(Object o) {
        return o == null ? "–" : o.toString();
    }

    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><th>").append(HtmlUtils.htmlEscape(label)).append("</th><td>")
                .append(HtmlUtils.htmlEscape(value == null ? "–" : value)).append("</td></tr>");
    }

    private static void rowClass(StringBuilder html, String label, String value, boolean ok) {
        html.append("<tr><th>").append(HtmlUtils.htmlEscape(label)).append("</th><td class=\"")
                .append(ok ? "ok" : "bad").append("\">").append(HtmlUtils.htmlEscape(value)).append("</td></tr>");
    }

    private static void cell(StringBuilder html, String value) {
        html.append("<td>").append(HtmlUtils.htmlEscape(value == null ? "–" : value)).append("</td>");
    }
}
