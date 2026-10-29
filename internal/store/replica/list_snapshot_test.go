package replica_test

import (
	"fmt"
	"reflect"
	"testing"

	"github.com/dbpprt/dieter/internal/model"
	"github.com/dbpprt/dieter/internal/replication/crdt"
	"github.com/dbpprt/dieter/internal/store"
	"github.com/dbpprt/dieter/internal/store/kanban"
	"github.com/dbpprt/dieter/internal/store/replica"
	"github.com/dbpprt/dieter/internal/testkit"
)

func TestPeerPagesStayInsideTheirPrefix(t *testing.T) {
	t.Parallel()
	s := store.New(t.TempDir())
	defer s.Close()
	identity, err := s.Replica.BindPeerAccount("account", "github:1", "daemon", "https://gateway.test")
	if err != nil {
		t.Fatal(err)
	}
	// Neighbours sort before and after the prefix, which spans three pages.
	inside := 2*crdt.PageSize + 7
	for kind, count := range map[string]int{"kv.a": crdt.PageSize, "kv.b": inside, "kv.c": crdt.PageSize} {
		for start := 0; start < count; start += crdt.PageSize {
			var records []crdt.Record
			for i := start; i < min(start+crdt.PageSize, count); i++ {
				r, err := crdt.Put(crdt.Record{}, kind, fmt.Sprintf("r_%04d", i), "actor", "", replica.RawValue(i), false)
				if err != nil {
					t.Fatal(err)
				}
				records = append(records, r)
			}
			if err = s.Replica.MergePeerRecords(identity, records); err != nil {
				t.Fatal(err)
			}
		}
	}
	view, err := s.Replica.OpenPeerSnapshot(identity.Account)
	if err != nil {
		t.Fatal(err)
	}
	defer view.Close()
	var ids []string
	if err = view.Each("kv.b/", func(r crdt.Record) error {
		if r.Kind != "kv.b" {
			t.Fatalf("page left its prefix: %s/%s", r.Kind, r.ID)
		}
		ids = append(ids, r.ID)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if len(ids) != inside || view.ViewQueries() != 3 {
		t.Fatalf("prefix pages: %d records in %d queries, want %d in 3", len(ids), view.ViewQueries(), inside)
	}
	for i, id := range ids {
		if id != fmt.Sprintf("r_%04d", i) {
			t.Fatalf("record %d is %s: pages skipped or repeated a key", i, id)
		}
	}
	matched := 0
	if err = view.EachMatching("kv.b/", "5", func(crdt.Record) error { matched++; return nil }); err != nil {
		t.Fatal(err)
	}
	if want := (inside + 4) / 10; matched != want {
		t.Fatalf("suffix match: %d, want %d", matched, want)
	}
}

func TestListSnapshotMaterializesOwnedStateWithinItsBound(t *testing.T) {
	t.Parallel()
	s, project, board := testkit.BoardStore(t, model.WorkflowReview)
	var ids []string
	for i := range 3 {
		card, err := s.Kanban.CreateCard(kanban.CreateCardInput{Project: project.ID, Board: board.ID, Title: fmt.Sprint("card ", i)})
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, card.ID)
	}
	projectCards := func(data replica.PeerData) []model.Card {
		t.Helper()
		var cards []model.Card
		for _, id := range ids {
			card, ok, err := kanban.SharedCard(data, id, model.Card{})
			if err != nil || !ok {
				t.Fatalf("projection of %s: %v %v", id, ok, err)
			}
			cards = append(cards, card)
		}
		return cards
	}
	_, paging, err := s.Replica.Snapshot()
	if err != nil {
		t.Fatal(err)
	}
	defer paging.Close()
	want := projectCards(paging)
	_, list, err := s.Replica.ListSnapshot()
	if err != nil {
		t.Fatal(err)
	}
	defer list.Close()
	if !list.InMemory() {
		t.Fatal("small replica was not materialized")
	}
	if got := projectCards(list); !reflect.DeepEqual(got, want) {
		t.Fatalf("in-memory projection differs from the paging one:\n%+v\n%+v", got, want)
	}

	// Listed cards own their data: changing one must not reach the next list.
	cards, err := s.Kanban.ListAllCards(false)
	if err != nil || len(cards) != len(ids) {
		t.Fatalf("list: %d cards, %v", len(cards), err)
	}
	for _, card := range cards {
		card.SharedBase["title"][0] = '!'
		for _, field := range card.StateFields {
			for _, v := range field.Versions {
				for actor := range v.Clock {
					v.Clock[actor] = 999
				}
			}
		}
	}
	again, err := s.Kanban.ListAllCards(false)
	if err != nil {
		t.Fatal(err)
	}
	for _, card := range again {
		if card.SharedBase["title"][0] == '!' {
			t.Fatal("a listed card aliased the next list's records")
		}
		for _, field := range card.StateFields {
			for _, v := range field.Versions {
				for _, n := range v.Clock {
					if n == 999 {
						t.Fatal("a listed card aliased the next list's clocks")
					}
				}
			}
		}
	}

	// Above the bound, lists keep the bounded pages.
	s.Replica.SetListSnapshotMaxBytes(0)
	_, bounded, err := s.Replica.ListSnapshot()
	if err != nil {
		t.Fatal(err)
	}
	defer bounded.Close()
	if bounded.InMemory() {
		t.Fatal("bound ignored")
	}
	if got := projectCards(bounded); !reflect.DeepEqual(got, want) {
		t.Fatal("bounded projection differs from the paging one")
	}
	if got, err := s.Kanban.ListAllCards(false); err != nil || len(got) != len(ids) {
		t.Fatalf("bounded list: %d cards, %v", len(got), err)
	}
}
