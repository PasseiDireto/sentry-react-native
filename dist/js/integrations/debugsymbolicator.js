import { __awaiter } from "tslib";
import { addGlobalEventProcessor, getCurrentHub } from "@sentry/core";
import { addContextToFrame, logger } from "@sentry/utils";
const INTERNAL_CALLSITES_REGEX = new RegExp(["ReactNativeRenderer-dev\\.js$", "MessageQueue\\.js$"].join("|"));
/** Tries to symbolicate the JS stack trace on the device. */
export class DebugSymbolicator {
    constructor() {
        /**
         * @inheritDoc
         */
        this.name = DebugSymbolicator.id;
    }
    /**
     * @inheritDoc
     */
    setupOnce() {
        // tslint:disable-next-line: cyclomatic-complexity
        addGlobalEventProcessor((event, hint) => __awaiter(this, void 0, void 0, function* () {
            const self = getCurrentHub().getIntegration(DebugSymbolicator);
            // tslint:disable: strict-comparisons
            if (!self || hint === undefined || hint.originalException === undefined) {
                return event;
            }
            const reactError = hint.originalException;
            // tslint:disable: no-unsafe-any
            const parseErrorStack = require("react-native/Libraries/Core/Devtools/parseErrorStack");
            const stack = parseErrorStack(reactError);
            // Ideally this should go into contexts but android sdk doesn't support it
            event.extra = Object.assign(Object.assign({}, event.extra), { componentStack: reactError.componentStack, jsEngine: reactError.jsEngine });
            if (__DEV__) {
                yield self._symbolicate(event, stack);
            }
            if (reactError.jsEngine === "hermes") {
                const convertedFrames = yield this._convertReactNativeFramesToSentryFrames(stack);
                this._replaceFramesInEvent(event, convertedFrames);
            }
            event.platform = "node"; // Setting platform node makes sure we do not show source maps errors
            // tslint:enable: no-unsafe-any
            // tslint:enable: strict-comparisons
            return event;
        }));
    }
    /**
     * Symbolicates the stack on the device talking to local dev server.
     * Mutates the passed event.
     */
    _symbolicate(event, stack) {
        return __awaiter(this, void 0, void 0, function* () {
            // tslint:disable: no-unsafe-any
            // tslint:disable: strict-comparisons
            try {
                const symbolicateStackTrace = require("react-native/Libraries/Core/Devtools/symbolicateStackTrace");
                const prettyStack = yield symbolicateStackTrace(stack);
                if (prettyStack) {
                    const stackWithoutInternalCallsites = prettyStack.filter((frame) => frame.file && frame.file.match(INTERNAL_CALLSITES_REGEX) === null);
                    const symbolicatedFrames = yield this._convertReactNativeFramesToSentryFrames(stackWithoutInternalCallsites);
                    this._replaceFramesInEvent(event, symbolicatedFrames);
                }
                else {
                    logger.error("The stack is null");
                }
            }
            catch (error) {
                logger.warn(`Unable to symbolicate stack trace: ${error.message}`);
            }
            // tslint:enable: no-unsafe-any
            // tslint:enable: strict-comparisons
        });
    }
    /**
     * Converts ReactNativeFrames to frames in the Sentry format
     * @param frames ReactNativeFrame[]
     */
    _convertReactNativeFramesToSentryFrames(frames) {
        return __awaiter(this, void 0, void 0, function* () {
            let getDevServer;
            try {
                if (__DEV__) {
                    getDevServer = require("react-native/Libraries/Core/Devtools/getDevServer");
                }
            }
            catch (_oO) {
                // We can't load devserver URL
            }
            // Below you will find lines marked with :HACK to prevent showing errors in the sentry ui
            // But since this is a debug only feature: This is Fine (TM)
            return Promise.all(frames.map((frame) => __awaiter(this, void 0, void 0, function* () {
                let inApp = !!frame.column && !!frame.lineNumber;
                inApp =
                    inApp &&
                        // tslint:disable-next-line: strict-type-predicates
                        frame.file !== undefined &&
                        !frame.file.includes("node_modules") &&
                        !frame.file.includes("native code");
                const newFrame = {
                    colno: frame.column,
                    filename: frame.file,
                    function: frame.methodName,
                    in_app: inApp,
                    lineno: inApp ? frame.lineNumber : undefined,
                    platform: inApp ? "javascript" : "node" // :HACK
                };
                if (inApp && __DEV__) {
                    // tslint:disable-next-line: no-unsafe-any
                    yield this._addSourceContext(newFrame, getDevServer);
                }
                return newFrame;
            })));
        });
    }
    /**
     * Replaces the frames in the exception of a error.
     * @param event Event
     * @param frames StackFrame[]
     */
    _replaceFramesInEvent(event, frames) {
        if (event.exception &&
            event.exception.values &&
            event.exception.values[0] &&
            event.exception.values[0].stacktrace) {
            event.exception.values[0].stacktrace.frames = frames.reverse();
        }
    }
    /**
     * This tries to add source context for in_app Frames
     *
     * @param frame StackFrame
     * @param getDevServer function from RN to get DevServer URL
     */
    _addSourceContext(frame, getDevServer) {
        return __awaiter(this, void 0, void 0, function* () {
            // tslint:disable: no-unsafe-any no-non-null-assertion
            const response = yield fetch(`${getDevServer().url}${frame
                .filename.replace(/\/+$/, "")
                .replace(/.*\//, "")}`, {
                method: "GET"
            });
            const content = yield response.text();
            const lines = content.split("\n");
            addContextToFrame(lines, frame);
            // tslint:enable: no-unsafe-any no-non-null-assertion
        });
    }
}
/**
 * @inheritDoc
 */
DebugSymbolicator.id = "DebugSymbolicator";
//# sourceMappingURL=debugsymbolicator.js.map