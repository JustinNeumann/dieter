package replica

import (
	"errors"
	"os"
	"path/filepath"

	"github.com/dbpprt/dieter/internal/platform/fault"
	"github.com/dbpprt/dieter/internal/replication/crdt"
	"github.com/dbpprt/dieter/internal/store/home"
)

// EnsureIdentity returns the peer identity, initializing an unenrolled local
// replica and its database under the central mutation lock.
func (s *Store) EnsureIdentity() (PeerIdentity, error) {
	if identity, err := s.PeerIdentity(); err == nil {
		return identity, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return identity, err
	}
	release, err := s.home.BeginWrite()
	if err != nil {
		return PeerIdentity{}, err
	}
	defer release()
	identity, err := s.WriterIdentity()
	if err != nil {
		return identity, err
	}
	_, err = s.peerDatabase(identity.Account)
	return identity, err
}

// WriterIdentity is the identity a writer publishes as. Call it under the
// central writer lock; on first creation it records a local identity.
// Unenrolled installations are local replicas. First enrollment adopts these
// records into the authenticated account; later account changes never copy them.
func (s *Store) WriterIdentity() (PeerIdentity, error) {
	identity, err := s.PeerIdentity()
	if err == nil {
		return identity, s.checkEnrollmentBinding(identity)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return identity, err
	}
	identity = PeerIdentity{Account: "local", DaemonID: home.NewID("local_"), Actor: home.NewID("actor_")}
	return identity, home.WriteJSON(filepath.Join(s.home.Root, "peers", "identity.json"), identity)
}

// Snapshot returns the identity and a pinned snapshot of its replica; an
// unenrolled store returns an empty in-memory state. Close the data.
func (s *Store) Snapshot() (PeerIdentity, PeerData, error) {
	if err := s.home.CheckStorageSchema(); err != nil {
		return PeerIdentity{}, PeerData{}, err
	}
	identity, err := s.PeerIdentity()
	if errors.Is(err, os.ErrNotExist) {
		return identity, PeerData{State: crdt.State{Records: map[string]crdt.Record{}}}, nil
	}
	if err != nil {
		return identity, PeerData{}, err
	}
	data, err := s.OpenPeerSnapshot(identity.Account)
	return identity, data, err
}

// ListSnapshot is Snapshot for projections over every card. Up to the
// replica's list snapshot bound it returns the pinned revision as an indexed
// in-memory state that this caller owns; larger replicas get the paging
// snapshot. Close the data.
func (s *Store) ListSnapshot() (PeerIdentity, PeerData, error) {
	identity, data, err := s.Snapshot()
	if err != nil || data.view == nil {
		return identity, data, err
	}
	state, ok, err := data.materialize(s.listSnapshotMaxBytes)
	if err != nil {
		data.Close()
		return identity, PeerData{}, err
	}
	if !ok {
		return identity, data, nil
	}
	data.Close()
	return identity, state, nil
}

// checkEnrollmentBinding refuses to publish as a peer identity that this
// machine no longer holds. After `dieter daemon unenroll` and a new enrollment
// the daemon credential (daemon/identity.json) names a new daemon ID while
// peers/identity.json still binds the old one until the next peer sync round
// calls BindPeerAccount. Records written in between would be owned by the old
// ID without a key that can sign them, so no peer would accept them and this
// machine could never continue them either.
func (s *Store) checkEnrollmentBinding(identity PeerIdentity) error {
	if identity.Account == "local" {
		return nil
	}
	credential, err := s.peerCredential()
	if err != nil {
		return err
	}
	if credential.ID != "" && credential.ID != identity.DaemonID {
		return fault.New(fault.FailedPrecondition, "this machine enrolled again as "+credential.ID+" but its peer store still binds "+identity.DaemonID+"; writes resume after the next peer sync round")
	}
	return nil
}
