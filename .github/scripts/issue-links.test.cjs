const assert = require("node:assert/strict");
const test = require("node:test");

const {
    closeLinkedIssues,
    extractClosingIssueNumbers,
    validateIssueReferences,
} = require("./issue-links.cjs");

function githubMock(issues, comments = []) {
    const calls = {
        comments: [],
        updates: [],
    };
    const github = {
        paginate: async () => comments,
        rest: {
            issues: {
                createComment: async (request) => calls.comments.push(request),
                get: async ({ issue_number: issueNumber }) => {
                    const issue = issues.get(issueNumber);
                    if (!issue) {
                        const error = new Error("Not Found");
                        error.status = 404;
                        throw error;
                    }
                    return { data: issue };
                },
                listComments: async () => [],
                update: async (request) => calls.updates.push(request),
            },
        },
    };

    return { calls, github };
}

test("extracts an issue number from an exact Closes line", () => {
    assert.deepEqual(extractClosingIssueNumbers("## Related issue\n\nCloses #42"), [42]);
});

test("accepts surrounding whitespace and case differences", () => {
    assert.deepEqual(extractClosingIssueNumbers("  CLOSES   #7  "), [7]);
});

test("extracts multiple issue numbers once in first-seen order", () => {
    const body = "Closes #3\nCloses #9\nCloses #3";

    assert.deepEqual(extractClosingIssueNumbers(body), [3, 9]);
});

test("ignores placeholders, inline prose, other keywords, and cross-repository references", () => {
    const body = [
        "Closes #<issue-number>",
        "This change Closes #12 after merge.",
        "Fixes #13",
        "Closes meet-me-duo/meet-me-server#14",
        "Closes #0",
    ].join("\n");

    assert.deepEqual(extractClosingIssueNumbers(body), []);
});

test("returns no issue numbers for an absent body", () => {
    assert.deepEqual(extractClosingIssueNumbers(null), []);
});

test("validates that references point to open issues", async () => {
    const issues = new Map([
        [1, { state: "open" }],
        [2, { state: "closed" }],
        [3, { state: "open", pull_request: {} }],
    ]);
    const { github } = githubMock(issues);

    const invalidReferences = await validateIssueReferences({
        github,
        owner: "meet-me-duo",
        repo: "meet-me-server",
        issueNumbers: [1, 2, 3, 4],
    });

    assert.deepEqual(invalidReferences, [
        "#2은(는) 열린 Issue가 아닙니다.",
        "#3은(는) Issue가 아닌 PR입니다.",
        "#4을(를) 찾을 수 없습니다.",
    ]);
});

test("comments once and closes an open issue as completed", async () => {
    const issues = new Map([[4, { state: "open" }]]);
    const { calls, github } = githubMock(issues);

    await closeLinkedIssues({
        github,
        owner: "meet-me-duo",
        repo: "meet-me-server",
        issueNumbers: [4],
        pullRequest: { number: 8, html_url: "https://github.com/meet-me-duo/meet-me-server/pull/8" },
        log: () => {},
    });

    assert.equal(calls.comments.length, 1);
    assert.match(calls.comments[0].body, /auto-close-pr:8/);
    assert.deepEqual(calls.updates, [
        {
            owner: "meet-me-duo",
            repo: "meet-me-server",
            issue_number: 4,
            state: "closed",
            state_reason: "completed",
        },
    ]);
});

test("does not duplicate an existing automation comment", async () => {
    const issues = new Map([[4, { state: "open" }]]);
    const { calls, github } = githubMock(issues, [
        { body: "<!-- auto-close-pr:8 -->\nalready recorded" },
    ]);

    await closeLinkedIssues({
        github,
        owner: "meet-me-duo",
        repo: "meet-me-server",
        issueNumbers: [4],
        pullRequest: { number: 8, html_url: "https://github.com/meet-me-duo/meet-me-server/pull/8" },
        log: () => {},
    });

    assert.equal(calls.comments.length, 0);
    assert.equal(calls.updates.length, 1);
});

test("leaves an already closed issue unchanged", async () => {
    const issues = new Map([[4, { state: "closed" }]]);
    const { calls, github } = githubMock(issues);
    const logs = [];

    await closeLinkedIssues({
        github,
        owner: "meet-me-duo",
        repo: "meet-me-server",
        issueNumbers: [4],
        pullRequest: { number: 8, html_url: "https://github.com/meet-me-duo/meet-me-server/pull/8" },
        log: (message) => logs.push(message),
    });

    assert.equal(calls.comments.length, 0);
    assert.equal(calls.updates.length, 0);
    assert.equal(logs.length, 1);
});
