package com.ghost.streaming.webrtc;

import com.ghost.streaming.api.VideoEncoder;

public class AdaptiveBitrateController {

    private final VideoEncoder encoder;
    private int currentBitrate;

    public AdaptiveBitrateController(VideoEncoder encoder, int initialBitrate) {
        this.encoder = encoder;
        this.currentBitrate = initialBitrate;
    }

    // Called frequently when WebRTC's GCC estimates new bandwidth
    public void onBandwidthEstimateChanged(int estimatedBitrateBps) {
        // Simple logic for scaffolding: keep bitrate within bounds
        int minBitrate = 500_000;
        int maxBitrate = 4_000_000;
        
        currentBitrate = Math.max(minBitrate, Math.min(estimatedBitrateBps, maxBitrate));
        if (encoder != null) {
            encoder.setBitrate(currentBitrate);
        }
    }
}
