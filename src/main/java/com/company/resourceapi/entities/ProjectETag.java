package com.company.resourceapi.entities;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.util.DigestUtils;

/**
 * Strong ETag of a project: a hash of what a client can change (external id, name, SDLC system), so it
 * changes exactly when the project does. Timestamps are left out: the stored ones are less precise
 * than the ones of a just-saved project, which would make the same state carry two ETags.
 */
public final class ProjectETag {

    private ProjectETag() {
    }

    public static String of(Project project) {
        var content = project.getId() + "\n" + project.getExternalId() + "\n" + project.getName() + "\n"
                + (project.getSdlcSystem() == null ? "" : project.getSdlcSystem().getId());
        return "\"" + DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8)) + "\"";
    }

    /**
     * Whether an {@code If-Match} header value is met by the project (strong comparison: a weak
     * {@code W/} tag never matches; {@code *} matches any existing project).
     */
    public static boolean matches(String ifMatch, Project project) {
        var current = of(project);
        return Arrays.stream(ifMatch.split(","))
                .map(String::trim)
                .anyMatch(tag -> tag.equals("*") || tag.equals(current));
    }
}
