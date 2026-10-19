package players

import (
	"encoding/json"
	"fmt"
	"sort"
	"time"
)

// Role commands are deliberately finite. No wildcard, arbitrary permission,
// console dispatch, OP, gamemode, execute, or permission-management grants.
var AllowedCommands = []string{"ban", "kick", "pardon", "fly"}

func ValidCommand(command string) bool {
	for _, v := range AllowedCommands {
		if command == v {
			return true
		}
	}
	return false
}
func ValidName(name string) bool { return namePattern.MatchString(name) }
func (s *Service) RoleCommands() (map[string][]string, error) {
	out := map[string][]string{}
	for _, role := range Catalog {
		out[role.ID] = []string{}
	}
	rows, e := s.DB.Query("SELECT role,command FROM role_commands ORDER BY command")
	if e != nil {
		return out, e
	}
	defer rows.Close()
	for rows.Next() {
		var role, command string
		if e = rows.Scan(&role, &command); e != nil {
			return out, e
		}
		if ValidRole(role) && ValidCommand(command) {
			out[role] = append(out[role], command)
		}
	}
	return out, rows.Err()
}
func (s *Service) SetCommands(role string, commands []string, actor string) error {
	if !ValidRole(role) || len(commands) > len(AllowedCommands) {
		return fmt.Errorf("invalid role or commands")
	}
	seen := map[string]bool{}
	for _, command := range commands {
		if !ValidCommand(command) || seen[command] {
			return fmt.Errorf("invalid or duplicate command")
		}
		seen[command] = true
	}
	sort.Strings(commands)
	tx, e := s.DB.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.Exec("DELETE FROM role_commands WHERE role=?", role); e != nil {
		return e
	}
	for _, command := range commands {
		if _, e = tx.Exec("INSERT INTO role_commands(role,command) VALUES(?,?)", role, command); e != nil {
			return e
		}
	}
	detail, _ := json.Marshal(map[string]any{"role": role, "commands": commands})
	if _, e = tx.Exec("INSERT INTO audit(at,username,server,action,result) VALUES(?,?,?,?,?)", time.Now().UTC().Format(time.RFC3339), actor, "network", "role-commands:"+string(detail), 200); e != nil {
		return e
	}
	return tx.Commit()
}
