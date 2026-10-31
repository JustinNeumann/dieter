package api

import (
	"strings"
	"testing"

	"connectrpc.com/connect"
	dieterv1 "github.com/dbpprt/dieter/internal/gen/dieter/v1"
	"github.com/dbpprt/dieter/internal/harness"
	"github.com/dbpprt/dieter/internal/model"
	"github.com/dbpprt/dieter/internal/store/kanban"
	"github.com/dbpprt/dieter/internal/testkit"
)

// Merging into the base branch is the person's: an agent turn may not start
// merge_pr or merge_local, for its own conversation or any other, while
// people (no turn token) still reach the operation's own checks.
func TestAgentTurnsCannotMerge(t *testing.T) {
	t.Parallel()
	data := testkit.Store(t)
	project, board := mailFixture(t, data)
	create := func(title string) model.Card {
		card, err := data.Kanban.CreateCard(kanban.CreateCardInput{Project: project.ID, Board: board.ID, Lane: model.LaneTodo, Title: title, Prompt: "Work", Provider: "mock", Model: "mock"})
		if err != nil {
			t.Fatal(err)
		}
		return card
	}
	working, other := create("Working"), create("Other")
	runner := vaultTurnRunner{started: make(chan harness.Request, 1), release: make(chan string, 1)}
	client, _ := newConnectTestClient(t, data, runner, testkit.MockCatalog())
	ctx := t.Context()
	agent := withHeaders(ctx, turnHeaders(startVaultTurn(t, client, runner, working.ID)))

	for _, kind := range []string{"merge_pr", "merge_local"} {
		for _, card := range []model.Card{working, other} {
			_, err := client.StartGitOperation(agent, &dieterv1.StartGitOperationRequest{CardId: card.ID, Kind: kind})
			wantCode(t, err, connect.CodePermissionDenied, kind+" from an agent turn")
			if !strings.Contains(err.Error(), "merging is the person's") {
				t.Fatalf("%s denial = %v", kind, err)
			}
		}
		// A person's request passes this check and meets the operation's own (this card has no pull request).
		if _, err := client.StartGitOperation(ctx, &dieterv1.StartGitOperationRequest{CardId: other.ID, Kind: kind}); err != nil && strings.Contains(err.Error(), "merging is the person's") {
			t.Fatalf("%s refused a person: %v", kind, err)
		}
	}
	// Preparing the pull request stays the agent's.
	if _, err := client.StartGitOperation(agent, &dieterv1.StartGitOperationRequest{CardId: other.ID, Kind: "refresh_pr"}); err != nil && strings.Contains(err.Error(), "merging is the person's") {
		t.Fatalf("refresh_pr refused as a merge: %v", err)
	}
}
