package org.earnlumens.mediastore.domain.media.model;

public enum ReportReason {
    // High severity
    SCAM,
    PHISHING,
    MALWARE,
    DOXXING,
    EXTREME_VIOLENCE,
    ILLEGAL,
    CSAM,
    SELF_HARM,

    // Medium severity
    HATE_SPEECH,
    NSFW,
    SPAM,
    IMPERSONATION,

    // Low severity
    COPYRIGHT,
    STOLEN_CONTENT,
    DUPLICATE,
    OFF_TOPIC,

    OTHER
}
