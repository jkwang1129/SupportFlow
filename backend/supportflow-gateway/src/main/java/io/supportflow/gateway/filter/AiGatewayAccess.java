package io.supportflow.gateway.filter;

import org.springframework.http.HttpMethod;

import java.util.Set;
import java.util.regex.Pattern;

/** Only paths used by the enterprise console are reachable through the public AI route. */
final class AiGatewayAccess {
    static final String ROUTE_ID = "supportflow-ai-service";
    private static final String BOT = "/api/ai/v1/bot";
    private static final Set<String> READ = Set.of(
            BOT + "/intents", BOT + "/dialogues", BOT + "/intent-stats", BOT + "/settings",
            BOT + "/models", BOT + "/training", BOT + "/training/match", "/api/ai/v1/kb/status");
    private static final Pattern JOB = Pattern.compile(BOT + "/training/jobs/[A-Za-z0-9_-]{1,64}");
    private static final Pattern VERSION = Pattern.compile(
            BOT + "/training/versions/[A-Za-z0-9_.-]{1,64}/(?:publish|rollback)");
    private static final Pattern INTENT = Pattern.compile(BOT + "/intents/[0-9]{1,19}");

    private AiGatewayAccess() {
    }

    static boolean isPublic(HttpMethod method, String path) {
        return HttpMethod.GET.equals(method) && "/api/ai/health".equals(path);
    }

    static boolean isConsole(HttpMethod method, String path) {
        if (HttpMethod.GET.equals(method)) {
            return READ.contains(path) || JOB.matcher(path).matches();
        }
        if (HttpMethod.PUT.equals(method)) {
            return (BOT + "/settings").equals(path) || (BOT + "/model").equals(path)
                    || INTENT.matcher(path).matches();
        }
        if (HttpMethod.POST.equals(method)) {
            return (BOT + "/intents").equals(path) || (BOT + "/training/jobs").equals(path)
                    || VERSION.matcher(path).matches();
        }
        return false;
    }
}
