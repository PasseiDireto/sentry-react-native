import { PromiseBuffer } from "@sentry/utils";
import { NATIVE } from "../wrapper";
/** Native Transport class implementation */
export class NativeTransport {
    constructor() {
        /** A simple buffer holding all requests. */
        this._buffer = new PromiseBuffer(30);
    }
    /**
     * @inheritDoc
     */
    sendEvent(event) {
        // TODO check if buffer is full like in node
        return this._buffer.add(NATIVE.sendEvent(event));
    }
    /**
     * @inheritDoc
     */
    close(timeout) {
        return this._buffer.drain(timeout);
    }
}
//# sourceMappingURL=native.js.map