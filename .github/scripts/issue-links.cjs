const CLOSING_ISSUE_PATTERN = /^\s*Closes\s+#([1-9]\d*)\s*$/gim;

function extractClosingIssueNumbers(body) {
    if (typeof body !== "string") {
        return [];
    }

    const issueNumbers = [];
    const seen = new Set();

    for (const match of body.matchAll(CLOSING_ISSUE_PATTERN)) {
        const issueNumber = Number(match[1]);
        if (!seen.has(issueNumber)) {
            seen.add(issueNumber);
            issueNumbers.push(issueNumber);
        }
    }

    return issueNumbers;
}

async function validateIssueReferences({ github, owner, repo, issueNumbers }) {
    const invalidReferences = [];

    for (const issueNumber of issueNumbers) {
        try {
            const { data } = await github.rest.issues.get({
                owner,
                repo,
                issue_number: issueNumber,
            });

            if (data.pull_request) {
                invalidReferences.push(`#${issueNumber}은(는) Issue가 아닌 PR입니다.`);
            } else if (data.state !== "open") {
                invalidReferences.push(`#${issueNumber}은(는) 열린 Issue가 아닙니다.`);
            }
        } catch (error) {
            if (error.status === 404) {
                invalidReferences.push(`#${issueNumber}을(를) 찾을 수 없습니다.`);
            } else {
                throw error;
            }
        }
    }

    return invalidReferences;
}

async function closeLinkedIssues({ github, owner, repo, issueNumbers, pullRequest, log }) {
    for (const issueNumber of issueNumbers) {
        const { data } = await github.rest.issues.get({
            owner,
            repo,
            issue_number: issueNumber,
        });

        if (data.pull_request) {
            throw new Error(`#${issueNumber}은(는) Issue가 아닌 PR입니다.`);
        }

        if (data.state !== "open") {
            log(`#${issueNumber}은(는) 이미 닫혀 있어 건너뜁니다.`);
            continue;
        }

        const marker = `<!-- auto-close-pr:${pullRequest.number} -->`;
        const comments = await github.paginate(
            github.rest.issues.listComments,
            { owner, repo, issue_number: issueNumber, per_page: 100 },
        );

        if (!comments.some((comment) => comment.body?.includes(marker))) {
            await github.rest.issues.createComment({
                owner,
                repo,
                issue_number: issueNumber,
                body: `${marker}\n${pullRequest.html_url}이 develop에 병합되어 이 Issue를 자동 종료합니다.`,
            });
        }

        await github.rest.issues.update({
            owner,
            repo,
            issue_number: issueNumber,
            state: "closed",
            state_reason: "completed",
        });
    }
}

module.exports = {
    closeLinkedIssues,
    extractClosingIssueNumbers,
    validateIssueReferences,
};
