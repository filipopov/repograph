package dev.repograph.agent;

import java.util.List;

/**
 * The model's plan for a cross-repo change.
 *
 * @param title short PR title, imperative
 * @param summary what changes and why, for every PR description
 * @param branch kebab-case branch suffix, the same in every repo
 * @param fields affected DTO/record/event fields as {@code Type.field}, for the impact appendix
 * @param repos the repositories to change; order is decided by Maven dependencies, not by the model
 * @param risks what reviewers should check, e.g. JSON consumers outside the workspace
 */
record ChangePlan(String title, String summary, String branch, List<String> fields, List<RepoChange> repos,
    List<String> risks) {

  record RepoChange(String repo, String why, String instructions) {
  }

  static final String SCHEMA = """
      {
        "type": "object",
        "additionalProperties": false,
        "required": ["title", "summary", "branch", "fields", "repos", "risks"],
        "properties": {
          "title":   {"type": "string", "description": "PR title, imperative, max 70 chars"},
          "summary": {"type": "string", "description": "2-4 sentences: what changes and why"},
          "branch":  {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{2,40}$"},
          "fields":  {"type": "array", "items": {"type": "string"},
                      "description": "Changed fields of DTOs/records/events as Type.field (old names)"},
          "repos": {
            "type": "array",
            "minItems": 1,
            "items": {
              "type": "object",
              "additionalProperties": false,
              "required": ["repo", "why", "instructions"],
              "properties": {
                "repo":         {"type": "string", "description": "Repository name exactly as in overview"},
                "why":          {"type": "string", "description": "Why this repo is affected, with path:line evidence"},
                "instructions": {"type": "string", "description": "Concrete edits for this repo incl. tests"}
              }
            }
          },
          "risks": {"type": "array", "items": {"type": "string"}}
        }
      }""";
}
