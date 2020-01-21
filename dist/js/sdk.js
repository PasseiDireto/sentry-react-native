import { defaultIntegrations, getCurrentHub, Integrations } from "@sentry/browser";
import { initAndBind, setExtra } from "@sentry/core";
import { RewriteFrames } from "@sentry/integrations";
import { ReactNativeClient } from "./client";
import { DebugSymbolicator, DeviceContext, ReactNativeErrorHandlers, Release } from "./integrations";
const IGNORED_DEFAULT_INTEGRATIONS = [
    "GlobalHandlers",
    "Breadcrumbs",
    "TryCatch" // We don't need this
];
/**
 * Inits the SDK
 */
export function init(options = {
    enableNative: true,
    enableNativeCrashHandling: true
}) {
    // tslint:disable: strict-comparisons
    if (options.defaultIntegrations === undefined) {
        options.defaultIntegrations = [
            new ReactNativeErrorHandlers(),
            new Release(),
            ...defaultIntegrations.filter(i => !IGNORED_DEFAULT_INTEGRATIONS.includes(i.name)),
            new Integrations.Breadcrumbs({
                console: false,
                fetch: false
            }),
            new DebugSymbolicator(),
            new RewriteFrames({
                iteratee: (frame) => {
                    if (frame.filename) {
                        frame.filename = frame.filename
                            .replace(/^file\:\/\//, "")
                            .replace(/^address at /, "")
                            .replace(/^.*\/[^\.]+(\.app|CodePush|.*(?=\/))/, "");
                        const appPrefix = "app://";
                        // We always want to have a tripple slash
                        frame.filename =
                            frame.filename.indexOf("/") === 0
                                ? `${appPrefix}${frame.filename}`
                                : `${appPrefix}/${frame.filename}`;
                    }
                    return frame;
                }
            }),
            new DeviceContext()
        ];
    }
    if (options.enableNative === undefined) {
        options.enableNative = true;
    }
    if (options.enableNativeCrashHandling === undefined) {
        options.enableNativeCrashHandling = true;
    }
    if (options.enableNativeNagger === undefined) {
        options.enableNativeNagger = true;
    }
    // tslint:enable: strict-comparisons
    initAndBind(ReactNativeClient, options);
}
/**
 * Sets the release on the event.
 */
export function setRelease(release) {
    setExtra("__sentry_release", release);
}
/**
 * Sets the dist on the event.
 */
export function setDist(dist) {
    setExtra("__sentry_dist", dist);
}
/**
 * If native client is available it will trigger a native crash.
 * Use this only for testing purposes.
 */
export function nativeCrash() {
    const client = getCurrentHub().getClient();
    if (client) {
        client.nativeCrash();
    }
}
//# sourceMappingURL=sdk.js.map