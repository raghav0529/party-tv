package com.raghav.karaoke.core;

import java.util.Map;

/** Server side of a game. Requests to /api/app/&lt;id&gt;/&lt;rest&gt; arrive here, with rest as the path (no leading slash). */
public interface AppBackend {
    /** Returns the JSON-serialisable reply. body is null for GET. Throw IllegalArgumentException for a bad request (400). */
    Object handle(String method, String path, Map<String, String> body);

    /** Called when the room moves away from this app. Called with the server's lock held, so keep it quick. */
    default void onLeave() {}
}
