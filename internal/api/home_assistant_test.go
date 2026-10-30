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
	"google.golang.org/protobuf/types/known/emptypb"
)

// Home Assistant access mirrors mail access: fixed at creation, granted by
// people, passed on only by an agent turn that has it. The add-on's relay asks
// GetTurnCaller which conversation a turn token belongs to.
func TestHomeAssistantAccessIsGrantedByPeopleAndTheTurnCallerNamesIt(t *testing.T) {
	t.Parallel()
	data := testkit.Store(t)
	project, board := mailFixture(t, data)
	create := func(title string, access bool) model.Card {
		card, err := data.Kanban.CreateCard(kanban.CreateCardInput{Project: project.ID, Board: board.ID, Lane: model.LaneTodo, Title: title, Prompt: "Work", Provider: "mock", Model: "mock", HomeAssistantAccess: access})
		if err != nil {
			t.Fatal(err)
		}
		return card
	}
	without, with := create("Without Home Assistant", false), create("With Home Assistant", true)
	runner := vaultTurnRunner{started: make(chan harness.Request, 1), release: make(chan string, 1)}
	client, _ := newConnectTestClient(t, data, runner, testkit.MockCatalog())
	ctx := t.Context()
	conversation := func(title string, access bool) *dieterv1.CreateConversationRequest {
		return &dieterv1.CreateConversationRequest{ProjectId: project.ID, BoardId: board.ID, Title: title, Prompt: "Work", Provider: "mock", Model: "mock", WorkspaceMode: "project", DeferStart: true, HomeAssistantAccess: access}
	}
	draft := func(name string, access bool) *dieterv1.ScheduleDraft {
		return &dieterv1.ScheduleDraft{ProjectId: project.ID, BoardId: board.ID, Name: name, Cron: "0 3 * * *", Timezone: "UTC", Action: "draft", TitleTemplate: name, PromptTemplate: "Work", Provider: "mock", Model: "mock", WorkspaceMode: "project", OpenCardPolicy: "skip_if_open", MisfirePolicy: "latest", HomeAssistantAccess: access}
	}

	// No agent turn: the operator, a forged token, a remote device.
	_, err := client.GetTurnCaller(ctx, &emptypb.Empty{})
	wantCode(t, err, connect.CodePermissionDenied, "caller without a token")
	_, err = client.GetTurnCaller(withHeaders(ctx, map[string]string{turnTokenMetadata: "dtt1:" + with.ID + ":turn:forged"}), &emptypb.Empty{})
	wantCode(t, err, connect.CodePermissionDenied, "caller with a forged token")

	request := startVaultTurn(t, client, runner, without.ID)
	agent := withHeaders(ctx, turnHeaders(request))
	caller, err := client.GetTurnCaller(agent, &emptypb.Empty{})
	if err != nil || caller.GetCardId() != without.ID || caller.GetProjectId() != project.ID || caller.GetHomeAssistantAccess() {
		t.Fatalf("caller without access = %v, %v", caller, err)
	}
	remote := withHeaders(ctx, map[string]string{operatorSubjectMetadata: "github:1", deviceIDMetadata: "dv_remote", turnTokenMetadata: request.Environment["DIETER_TURN_TOKEN"]})
	_, err = client.GetTurnCaller(remote, &emptypb.Empty{})
	wantCode(t, err, connect.CodePermissionDenied, "a remote device never passes a turn token on")
	_, err = client.CreateCard(agent, conversation("Escalated card", true))
	wantCode(t, err, connect.CodePermissionDenied, "agent card with Home Assistant")
	if !strings.Contains(err.Error(), "a conversation without Home Assistant access cannot grant it") {
		t.Fatalf("denial = %v", err)
	}
	_, err = client.CreateSchedule(agent, &dieterv1.SaveScheduleRequest{Schedule: draft("Escalated schedule", true)})
	wantCode(t, err, connect.CodePermissionDenied, "agent schedule with Home Assistant")
	if plain, err := client.CreateCard(agent, conversation("Plain card", false)); err != nil || plain.GetHomeAssistantAccess() {
		t.Fatalf("agent card without access = %v, %v", plain, err)
	}
	runner.release <- "done"
	waitForIdle(t, data, without.ID)
	// The token stops working with its turn.
	_, err = client.GetTurnCaller(agent, &emptypb.Empty{})
	wantCode(t, err, connect.CodePermissionDenied, "caller after the turn")

	request = startVaultTurn(t, client, runner, with.ID)
	agent = withHeaders(ctx, turnHeaders(request))
	if caller, err = client.GetTurnCaller(agent, &emptypb.Empty{}); err != nil || caller.GetCardId() != with.ID || !caller.GetHomeAssistantAccess() {
		t.Fatalf("caller with access = %v, %v", caller, err)
	}
	if granted, err := client.CreateCard(agent, conversation("Granted card", true)); err != nil || !granted.GetHomeAssistantAccess() {
		t.Fatalf("agent with access passes it on = %v, %v", granted, err)
	}
	runner.release <- "done"
	waitForIdle(t, data, with.ID)

	// People always may; the flag is stored, and a fork starts without it.
	created, err := client.CreateCard(ctx, conversation("Operator card", true))
	if err != nil || !created.GetHomeAssistantAccess() {
		t.Fatalf("operator card = %v, %v", created, err)
	}
	if stored, err := data.Kanban.ResolveCard(created.GetId()); err != nil || !stored.HomeAssistantAccess {
		t.Fatalf("stored card = %+v, %v", stored, err)
	}
	if schedule, err := client.CreateSchedule(ctx, &dieterv1.SaveScheduleRequest{Schedule: draft("Operator schedule", true)}); err != nil || !schedule.GetHomeAssistantAccess() {
		t.Fatalf("operator schedule = %v, %v", schedule, err)
	}
	if forked, err := client.ForkChat(ctx, &dieterv1.ForkChatRequest{SourceCardId: with.ID, Title: "Fork"}); err != nil || forked.GetHomeAssistantAccess() {
		t.Fatalf("fork = %v, %v", forked, err)
	}
}
