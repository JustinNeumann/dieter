import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import {
  createQuestionRelay,
  declinedQuestions,
  isQuestionsOutput,
  questionToolResult,
} from "./host-questions.mjs";

const input = {
  allowPartialAnswers: true,
  questions: [{ id: "question-1", question: "Which?", options: [{ id: "a", label: "A" }] }],
};

test("without an endpoint every question is declined", async () => {
  const relay = createQuestionRelay({ url: "", sessionId: "c_1" });
  assert.equal(relay.enabled, false);
  assert.deepEqual(await relay.ask("tool-1", input), declinedQuestions());
});

test("registers the question, polls until the host answered and returns the tool output", async () => {
  const seen = { posts: [], polls: 0 };
  const answer = { action: "answered", answers: { "question-1": { optionIds: ["a"] } } };
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      res.setHeader("content-type", "application/json");
      if (req.method === "POST" && req.url === "/api/questions") {
        seen.posts.push(JSON.parse(body));
        return res.end("{}");
      }
      if (req.method === "GET" && req.url === "/api/questions/tool-1") {
        seen.polls += 1;
        return res.end(
          JSON.stringify(seen.polls < 3 ? { done: false } : { done: true, output: answer }),
        );
      }
      res.statusCode = 404;
      res.end("{}");
    });
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const url = `http://127.0.0.1:${server.address().port}/api/questions/`;
  try {
    const relay = createQuestionRelay({ url, sessionId: "c_42", pollMs: 10 });
    const output = await relay.ask("tool-1", input);
    assert.deepEqual(output, answer);
    assert.deepEqual(seen.posts, [{ id: "tool-1", session: "c_42", input }]);
    assert.equal(seen.polls, 3);
    assert.deepEqual(questionToolResult("tool-1", output), {
      type: "tool-result",
      toolCallId: "tool-1",
      toolName: "askUserQuestions",
      output: { type: "json", value: answer },
    });
  } finally {
    server.close();
  }
});

test("an aborted turn stops polling with a cancelled output; outages are retried", async () => {
  let polls = 0;
  const server = createServer((req, res) => {
    req.on("data", () => {});
    req.on("end", () => {
      if (req.method === "POST") return res.end("{}");
      polls += 1;
      res.statusCode = polls === 1 ? 500 : 200;
      res.end(JSON.stringify({ done: false }));
    });
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const controller = new AbortController();
  try {
    const relay = createQuestionRelay({
      url: `http://127.0.0.1:${server.address().port}`,
      sessionId: "c_1",
      signal: controller.signal,
      pollMs: 5,
    });
    const pending = relay.ask("tool-2", input);
    await new Promise((r) => setTimeout(r, 60));
    controller.abort();
    assert.deepEqual(await pending, { action: "cancelled" });
    assert.ok(polls >= 2);
  } finally {
    server.close();
  }
});

test("validates host answers", () => {
  assert.equal(isQuestionsOutput({ action: "declined" }), true);
  assert.equal(isQuestionsOutput({ action: "answered", answers: {} }), true);
  assert.equal(isQuestionsOutput({ action: "answered" }), false);
  assert.equal(isQuestionsOutput({ action: "nope" }), false);
  assert.equal(isQuestionsOutput(null), false);
});
