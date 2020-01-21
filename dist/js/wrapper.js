import { NativeModules } from "react-native";
const { RNSentry } = NativeModules;
/**
 * Our internal interface for calling native functions
 */
export const NATIVE = {
    /**
     * Sending the event over the bridge to native
     * @param event Event
     */
    sendEvent(event) {
        // tslint:disable-next-line: no-unsafe-any
        return RNSentry.sendEvent(event);
    }
};
//# sourceMappingURL=wrapper.js.map