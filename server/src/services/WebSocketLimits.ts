import { config } from '../config';

// Bound message bytes and structural memory before application authentication.
export const websocketLimits = {
  maxPayload: config.maxUploadBytes + 4100,
  maxFragments: 1024,
  maxBufferedChunks: 4096,
};
