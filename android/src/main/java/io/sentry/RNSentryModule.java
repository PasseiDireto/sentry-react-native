package io.sentry;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.sentry.android.core.AnrIntegration;
import io.sentry.android.core.NdkIntegration;
import io.sentry.android.core.SentryAndroid;
import io.sentry.core.Integration;
import io.sentry.core.SentryOptions;
import io.sentry.core.UncaughtExceptionHandlerIntegration;
import io.sentry.core.protocol.SentryException;


@ReactModule(name = RNSentryModule.NAME)
public class RNSentryModule extends ReactContextBaseJavaModule {

    public static final String NAME = "RNSentry";

    final static Logger logger = Logger.getLogger("react-native-sentry");

    private static PackageInfo packageInfo;
    private SentryOptions sentryOptions;

    public RNSentryModule(ReactApplicationContext reactContext) {
        super(reactContext);
        RNSentryModule.packageInfo = getPackageInfo(reactContext);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("nativeClientAvailable", true);
        constants.put("nativeTransport", true);
        return constants;
    }

    @ReactMethod
    public void startWithDsnString(String dsnString, final ReadableMap rnOptions, Promise promise) {
        SentryAndroid.init(this.getReactApplicationContext(), options -> {
            options.setDsn(dsnString);

            if (rnOptions.hasKey("debug") && rnOptions.getBoolean("debug")) {
                options.setDebug(true);
            }
            if (rnOptions.hasKey("environment") && rnOptions.getString("environment") != null) {
                options.setEnvironment(rnOptions.getString("environment"));
            }
            if (rnOptions.hasKey("release") && rnOptions.getString("release") != null) {
                options.setRelease(rnOptions.getString("release"));
            }
            if (rnOptions.hasKey("dist") && rnOptions.getString("dist") != null) {
                options.setDist(rnOptions.getString("dist"));
            }

            options.setBeforeSend((event, hint) -> {
                // React native internally throws a JavascriptException
                // Since we catch it before that, we don't want to send this one
                // because we would send it twice
                try {
                    SentryException ex = event.getExceptions().get(0);
                    if (null != ex && ex.getType().contains("JavascriptException")) {
                        return null;
                    }
                } catch (Exception e) {
                    // We do nothing
                }
                return event;
            });


            for (Iterator<Integration> iterator = options.getIntegrations().iterator(); iterator.hasNext(); ) {
            Integration integration = iterator.next();
                if (rnOptions.hasKey("enableNativeCrashHandling") &&
                        !rnOptions.getBoolean("enableNativeCrashHandling")) {
                    if (integration instanceof UncaughtExceptionHandlerIntegration ||
                            integration instanceof AnrIntegration ||
                            integration instanceof NdkIntegration) {
                        iterator.remove();
                    }
                }
            }

            logger.info(String.format("Native Integrations '%s'", options.getIntegrations().toString()));
            sentryOptions = options;
        });

        logger.info(String.format("startWithDsnString '%s'", dsnString));
        promise.resolve(true);
    }

    @ReactMethod
    public void setLogLevel(int level) {
        logger.setLevel(this.logLevel(level));
    }

    @ReactMethod
    public void crash() {
        throw new RuntimeException("TEST - Sentry Client Crash (only works in release mode)");
    }

    @ReactMethod
    public void fetchRelease(Promise promise) {
        WritableMap release = Arguments.createMap();
        release.putString("id", packageInfo.packageName);
        release.putString("version", packageInfo.versionName);
        release.putString("build", String.valueOf(packageInfo.versionCode));
        promise.resolve(release);
    }

    @ReactMethod
    public void deviceContexts(Promise promise) {
        EventBuilder eventBuilder = new EventBuilder();
        androidHelper.helpBuildingEvent(eventBuilder);
        Event event = eventBuilder.build();

        WritableMap params = Arguments.createMap();

        for (Map.Entry<String, Map<String, Object>> data : event.getContexts().entrySet()) {
            params.putMap(data.getKey(), MapUtil.toWritableMap(data.getValue()));
        }

        promise.resolve(params);
    }

    @ReactMethod
    public void extraUpdated(ReadableMap extra) {
        if (extra.hasKey("__sentry_release")) {
            sentryClient.release = extra.getString("__sentry_release");
        }
        if (extra.hasKey("__sentry_dist")) {
            sentryClient.dist = extra.getString("__sentry_dist");
        }
    }

    @ReactMethod
    public void sendEvent(ReadableMap event, Promise promise) {
        ReadableNativeMap castEvent = (ReadableNativeMap)event;

//        EventBuilder eventBuilder = new EventBuilder()
//                .withLevel(eventLevel(castEvent));

        EventBuilder eventBuilder;
        if (event.hasKey("event_id")) {
            UUID eventId = UUID.fromString(event.getString("event_id").replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                    "$1-$2-$3-$4-$5"));
            eventBuilder = new EventBuilder(eventId).withLevel(eventLevel(castEvent));
        } else {
            logger.info("Event has no event_id");
            eventBuilder = new EventBuilder().withLevel(eventLevel(castEvent));
        }

        androidHelper.helpBuildingEvent(eventBuilder);

        if (event.hasKey("breadcrumbs")) {
            ReadableNativeArray breadcrumbs = (ReadableNativeArray)event.getArray("breadcrumbs");
            ArrayList<Breadcrumb> eventBreadcrumbs = new ArrayList<Breadcrumb>();
            for (int i = 0; i < breadcrumbs.size(); i++) {
                ReadableNativeMap breadcrumb = breadcrumbs.getMap(i);
                BreadcrumbBuilder breadcrumbBuilder = new BreadcrumbBuilder();
                if (breadcrumb.hasKey("category")) {
                    breadcrumbBuilder.setCategory(breadcrumb.getString("category"));
                }

                if (breadcrumb.hasKey("type") && breadcrumb.getString("type") != null) {
                    String typeString = breadcrumb.getString("type").toUpperCase();
                    try {
                        breadcrumbBuilder.setType(Breadcrumb.Type.valueOf(typeString));
                    } catch (IllegalArgumentException e) {
                        //don't copy over invalid breadcrumb 'type' value
                    }
                }

                if (breadcrumb.hasKey("level") && breadcrumb.getString("level") != null) {
                    String levelString = breadcrumb.getString("level").toUpperCase();
                    try {
                        breadcrumbBuilder.setLevel(Breadcrumb.Level.valueOf(levelString));
                    } catch (IllegalArgumentException e) {
                        //don't copy over invalid breadcrumb 'level' value
                    }
                }

                try {
                    if (breadcrumb.hasKey("data") && breadcrumb.getMap("data") != null) {
                        Map<String, String> newData = new HashMap<>();
                        for (Map.Entry<String, Object> data : breadcrumb.getMap("data").toHashMap().entrySet()) {
                            newData.put(data.getKey(), data.getValue() != null ? data.getValue().toString() : null);
                        }

                        // in case a `status_code` entry got accidentally stringified as a float
                        if (newData.containsKey("status_code")) {
                              String value = newData.get("status_code");
                              newData.put(
                                  "status_code",
                                  value.endsWith(".0") ? value.replace(".0", "") : value
                              );
                        }

                        breadcrumbBuilder.setData(newData);
                    }
                } catch (UnexpectedNativeTypeException e) {
                    logger.warning("Discarded breadcrumb.data since it was not an object");
                } catch (ClassCastException e) { // This needs to be here for RN < 0.60
                    logger.warning("Discarded breadcrumb.data since it was not an object");
                }

                if (breadcrumb.hasKey("message")) {
                    breadcrumbBuilder.setMessage(breadcrumb.getString("message"));
                } else {
                    breadcrumbBuilder.setMessage("");
                }
                eventBreadcrumbs.add(i, breadcrumbBuilder.build());
            }
            if (eventBreadcrumbs.size() > 0) {
                eventBuilder.withBreadcrumbs(eventBreadcrumbs);
            }
        }

        if (event.hasKey("message")) {
            String message = "";
            try {
                message = event.getString("message");
            } catch (UnexpectedNativeTypeException e) {
                // Do nothing
            } catch (ClassCastException e) { // This needs to be here for RN < 0.60
                // Do nothing
            } finally {
                try {
                    message = event.getMap("message").toString();
                } catch (UnexpectedNativeTypeException e) {
                    // Do nothing
                } catch (ClassCastException e) { // This needs to be here for RN < 0.60
                    // Do nothing
                }
            }
            eventBuilder.withMessage(message);
        }

        if (event.hasKey("logger")) {
            eventBuilder.withLogger(event.getString("logger"));
        }

        if (event.hasKey("user")) {
            UserBuilder userBuilder = getUserBuilder(event.getMap("user"));
            User builtUser = userBuilder.build();
            UserInterface userInterface = new UserInterface(
                    builtUser.getId(),
                    builtUser.getUsername(),
                    null,
                    builtUser.getEmail(),
                    builtUser.getData()
            );
            eventBuilder.withSentryInterface(userInterface);
        }

        if (castEvent.hasKey("extra")) {
            for (Map.Entry<String, Object> entry : castEvent.getMap("extra").toHashMap().entrySet()) {
                eventBuilder.withExtra(entry.getKey(), entry.getValue());
            }
        }

        if (event.hasKey("fingerprint")) {
            ReadableArray fingerprint = event.getArray("fingerprint");
            ArrayList<String> print = new ArrayList<String>(fingerprint.size());
            for(int i = 0; i < fingerprint.size(); ++i) {
                print.add(i, fingerprint.getString(i));
            }
            eventBuilder.withFingerprint(print);
        }

        if (castEvent.hasKey("tags")) {
            for (Map.Entry<String, Object> entry : castEvent.getMap("tags").toHashMap().entrySet()) {
                String tagValue = entry.getValue() != null ? entry.getValue().toString() : "INVALID_TAG";
                eventBuilder.withTag(entry.getKey(), tagValue);
            }
        }

        if (event.hasKey("exception")) {
            ReadableNativeArray exceptionValues = (ReadableNativeArray)event.getMap("exception").getArray("values");
            ReadableNativeMap exception = exceptionValues.getMap(0);
            if (exception.hasKey("stacktrace")) {
                ReadableNativeMap stacktrace = exception.getMap("stacktrace");
                // temporary solution until final fix
                // https://github.com/getsentry/sentry-react-native/issues/742
                if (stacktrace.hasKey("frames")) {
                    ReadableNativeArray frames = (ReadableNativeArray)stacktrace.getArray("frames");
                    if (exception.hasKey("value")) {
                        addExceptionInterface(eventBuilder, exception.getString("type"), exception.getString("value"), frames);
                    } else {
                        // We use type/type here since this indicates an Unhandled Promise Rejection
                        // https://github.com/getsentry/react-native-sentry/issues/353
                        addExceptionInterface(eventBuilder, exception.getString("type"), exception.getString("type"), frames);
                    }
                }
            }
        }

        if (event.hasKey("environment")) {
            eventBuilder.withEnvironment(event.getString("environment"));
        }


        if (event.hasKey("release")) {
            eventBuilder.withRelease(event.getString("release"));
        } else {
            eventBuilder.withRelease(null);
        }

        if (event.hasKey("dist")) {
            eventBuilder.withDist(event.getString("dist"));
        } else {
            eventBuilder.withDist(null);
        }

        Event builtEvent = eventBuilder.build();

        if (event.hasKey("sdk")) {
            ReadableNativeMap sdk = (ReadableNativeMap)event.getMap("sdk");
            Set<String> sdkIntegrations = new HashSet<>();
            if (sdk.hasKey("integrations")) {
                ReadableNativeArray integrations = (ReadableNativeArray)sdk.getArray("integrations");
                for(int i = 0; i < integrations.size(); ++i) {
                    sdkIntegrations.add(integrations.getString(i));
                }
            }
        } catch (Exception e) {
            logger.info("Error reading envelope");
        }
        promise.resolve(true);
    }

    @ReactMethod
    public void getStringBytesLength(String payload, Promise promise) {
        try {
            promise.resolve(payload.getBytes("UTF-8").length);
        } catch (UnsupportedEncodingException e) {
            promise.reject(e);
        }
    }

    private static PackageInfo getPackageInfo(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            logger.info("Error getting package info.");
            return null;
        }
    }

    private Level logLevel(int level) {
        switch (level) {
            case 1:
                return Level.SEVERE;
            case 2:
                return Level.INFO;
            case 3:
                return Level.ALL;
            default:
                return Level.OFF;
        }
    }
}
