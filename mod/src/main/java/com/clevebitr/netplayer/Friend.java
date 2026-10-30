package com.clevebitr.netplayer;

import com.clevebitr.netplayer.core.Profile;

public final class Friend {
    public final Profile profile;
    public String note = "";
    public String mcName = "";
    public String mcUuid = "";
    public long addedAt = System.currentTimeMillis();

    public Friend(Profile profile) {
        this.profile = profile;
    }

    public String label() {
        return note == null || note.isBlank() ? profile.shortName() : note;
    }
}
