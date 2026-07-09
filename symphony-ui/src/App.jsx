import React, { useState, useEffect } from 'react';

const API_BASE = 'http://localhost:8091/api/v1';

// SVG Icons
const SearchIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
);
const GridIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="7" height="9"></rect><rect x="14" y="3" width="7" height="5"></rect><rect x="14" y="12" width="7" height="9"></rect><rect x="3" y="16" width="7" height="5"></rect></svg>
);
const UploadIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
);
const ChatIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>
);
const ChartIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line></svg>
);
const SunIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>
);
const MoonIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>
);

function App() {
  const [view, setView] = useState('marketplace'); // 'marketplace' | 'onboard' | 'chat' | 'analytics'
  const [theme, setTheme] = useState(() => localStorage.getItem('symphony-theme') || 'dark');
  const [userRole, setUserRole] = useState(() => localStorage.getItem('symphony-role') || 'Employee');
  
  const [agents, setAgents] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('All');
  const [loading, setLoading] = useState(false);
  const [manifestText, setManifestText] = useState(JSON.stringify({
    "manifestVersion": "1.0",
    "name": "payroll-manager",
    "version": "1.0.0",
    "description": "Reviews corporate staff payroll files and executes salary disbursement.",
    "endpoints": {
      "execution": "https://payroll.internal.net/api/v1/disburse",
      "health": "https://payroll.internal.net/health"
    },
    "parameters": [
      {
        "name": "departmentCode",
        "type": "string",
        "description": "Department code (e.g. ENG, HR)",
        "required": true
      }
    ],
    "security": {
      "requiresRBAC": true,
      "authorizedRoles": ["Admin", "HR"]
    }
  }, null, 2));

  const [onboardLogs, setOnboardLogs] = useState([]);
  const [selectedAgent, setSelectedAgent] = useState(null);

  // Chat/Supervisor state
  const [chatInput, setChatInput] = useState('');
  const [chatMessages, setChatMessages] = useState([
    { sender: 'supervisor', text: 'Hello! I am the SymphonyAI Supervisor. Explain your requirements, and I will route them to the appropriate enterprise agent.', time: new Date().toLocaleTimeString(), routing: null }
  ]);
  const [selectedRoutingMetadata, setSelectedRoutingMetadata] = useState(null);
  const [chatLoading, setChatLoading] = useState(false);

  // Gaps & Telemetry state
  const [gaps, setGaps] = useState([]);
  const [telemetry, setTelemetry] = useState([]);
  const [clusteringLoading, setClusteringLoading] = useState(false);

  // Sync theme
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('symphony-theme', theme);
  }, [theme]);

  // Sync userRole
  useEffect(() => {
    localStorage.setItem('symphony-role', userRole);
  }, [userRole]);

  const toggleTheme = () => {
    setTheme(prev => prev === 'dark' ? 'light' : 'dark');
  };

  // Fetch agents
  const fetchAgents = async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/agents`);
      if (res.ok) {
        const data = await res.json();
        setAgents(data);
      }
    } catch (err) {
      console.error("Failed loading agents", err);
    } finally {
      setLoading(false);
    }
  };

  // Fetch gaps and telemetry
  const fetchAnalytics = async () => {
    try {
      const resGaps = await fetch(`${API_BASE}/admin/gaps`);
      if (resGaps.ok) setGaps(await resGaps.json());

      const resTel = await fetch(`${API_BASE}/admin/telemetry`);
      if (resTel.ok) setTelemetry(await resTel.json());
    } catch (err) {
      console.error("Failed loading analytics", err);
    }
  };

  useEffect(() => {
    if (view === 'marketplace') {
      fetchAgents();
      setSelectedAgent(null);
    } else if (view === 'analytics') {
      fetchAnalytics();
    }
  }, [view]);

  // Search agents
  const handleSearch = async (e) => {
    e.preventDefault();
    if (!searchQuery.trim()) {
      fetchAgents();
      return;
    }
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/agents/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: searchQuery, limit: 10 })
      });
      if (res.ok) {
        setAgents(await res.json());
      }
    } catch (err) {
      console.error("Search failed", err);
    } finally {
      setLoading(false);
    }
  };

  // Onboard agent manifest
  const handleOnboard = async (e) => {
    e.preventDefault();
    setOnboardLogs([]);
    const addLog = (text, type = 'info') => {
      setOnboardLogs(prev => [...prev, { text, type, time: new Date().toLocaleTimeString() }]);
    };

    try {
      addLog("Parsing manifest payload...", "info");
      let parsed = JSON.parse(manifestText);
      addLog(`Validating JSON Schema parameters for '${parsed.name || 'unnamed'}'...`, "info");
      if (!parsed.name || !parsed.description || !parsed.endpoints) {
        addLog("Schema Error: Missing core properties.", "error");
        return;
      }
      addLog("Validation PASS: Schema matches specifications.", "success");

      if (parsed.security && parsed.security.requiresRBAC) {
        addLog(`Security config: RBAC active, limited to: ${JSON.stringify(parsed.security.authorizedRoles)}`, "info");
      }

      addLog(`Loopback Check: Querying health at ${parsed.endpoints.health}...`, "info");
      const res = await fetch(`${API_BASE}/agents/onboard`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parsed)
      });

      if (res.ok) {
        const data = await res.json();
        addLog(`Health Status: Endpoint active (HTTP 200 OK).`, "success");
        addLog(`Embedding: Vector representation generated.`, "success");
        addLog(`Registry: Agent '${data.name}' registered (ID: ${data.id})`, "success");
        addLog("Onboarding success.", "success");
      } else {
        addLog(`Verification Failed: Endpoint unreachable or validation mismatch.`, "error");
      }
    } catch (err) {
      addLog(`Network Failure: ${err.message}`, "error");
    }
  };

  // Conversational Supervisor routing
  const handleChatSend = async (e) => {
    e.preventDefault();
    if (!chatInput.trim()) return;

    const userQuery = chatInput;
    setChatInput('');
    setChatMessages(prev => [...prev, { sender: 'user', text: userQuery, time: new Date().toLocaleTimeString() }]);
    setChatLoading(true);

    try {
      const res = await fetch(`${API_BASE}/supervisor/chat`, {
        method: 'POST',
        headers: { 
          'Content-Type': 'application/json',
          'X-User-Role': userRole
        },
        body: JSON.stringify({ query: userQuery })
      });

      if (res.ok) {
        const data = await res.json();
        const msg = {
          sender: 'supervisor',
          text: data.textResponse,
          time: new Date().toLocaleTimeString(),
          piiSanitized: data.piiSanitized,
          sanitizedFields: data.sanitizedFields,
          routing: {
            agent: data.matchedAgent,
            params: data.extractedParameters,
            latency: data.latencyMs,
            status: data.status,
            executionSteps: data.executionSteps
          }
        };
        setChatMessages(prev => [...prev, msg]);
        setSelectedRoutingMetadata(msg.routing);
      }
    } catch (err) {
      console.error("Chat failure", err);
    } finally {
      setChatLoading(false);
    }
  };

  // Trigger manual gap clustering
  const triggerClustering = async () => {
    setClusteringLoading(true);
    try {
      const res = await fetch(`${API_BASE}/admin/gaps/trigger-cluster`, { method: 'POST' });
      if (res.ok) {
        await fetchAnalytics();
        alert("Innovation gap clustering completed successfully!");
      }
    } catch (err) {
      console.error("Clustering trigger failed", err);
    } finally {
      setClusteringLoading(false);
    }
  };

  const filteredAgents = agents.filter(a => {
    if (selectedCategory === 'All') return true;
    const name = a.name.toLowerCase();
    const desc = a.description.toLowerCase();
    if (selectedCategory === 'Legal') return name.includes('legal') || desc.includes('legal');
    if (selectedCategory === 'HR') return name.includes('hr') || name.includes('payroll') || desc.includes('payroll');
    if (selectedCategory === 'Finance') return name.includes('finance') || desc.includes('finance');
    if (selectedCategory === 'DevTools') return name.includes('summary') || name.includes('parse') || desc.includes('summar');
    return true;
  });

  return (
    <div className="app-container">
      {/* Sidebar navigation panel */}
      <aside className="sidebar">
        <div className="brand">
          <span style={{ fontSize: '1.6rem' }}>💎</span>
          <span>SymphonyAI</span>
        </div>
        
        <nav style={{ marginTop: '1rem' }}>
          <ul className="nav-links">
            <li className={`nav-item ${view === 'marketplace' ? 'active' : ''}`} onClick={() => setView('marketplace')}>
              <GridIcon />
              <span>Registry Hub</span>
            </li>
            <li className={`nav-item ${view === 'onboard' ? 'active' : ''}`} onClick={() => setView('onboard')}>
              <UploadIcon />
              <span>Onboard Agent</span>
            </li>
            <li className={`nav-item ${view === 'chat' ? 'active' : ''}`} onClick={() => setView('chat')}>
              <ChatIcon />
              <span>Supervisor Chat</span>
            </li>
            <li className={`nav-item ${view === 'analytics' ? 'active' : ''}`} onClick={() => setView('analytics')}>
              <ChartIcon />
              <span>Demand & Gaps</span>
            </li>
          </ul>
        </nav>

        {/* SSO Role Switcher Panel */}
        <div style={{ marginTop: 'auto', borderTop: '1px solid var(--border-color)', paddingTop: '1.25rem' }}>
          <div className="form-group" style={{ marginBottom: '1rem' }}>
            <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>Active SSO User Role</label>
            <select 
              className="form-control"
              style={{ padding: '0.5rem', width: '100%', cursor: 'pointer' }}
              value={userRole}
              onChange={e => setUserRole(e.target.value)}
            >
              <option value="Employee">Employee (Standard)</option>
              <option value="HR">HR Payroll Specialist</option>
              <option value="Admin">System Administrator</option>
            </select>
          </div>
          
          <button className="theme-toggle-btn" onClick={toggleTheme}>
            {theme === 'dark' ? (
              <><SunIcon /><span>Light Theme</span></>
            ) : (
              <><MoonIcon /><span>Dark Theme</span></>
            )}
          </button>
        </div>
      </aside>

      {/* Main Panel Content */}
      <main className="main-content">
        
        {/* VIEW 1: MARKETPLACE */}
        {view === 'marketplace' && (
          <>
            <div>
              <h1 style={{ fontSize: '2.5rem', fontWeight: '700', marginBottom: '0.5rem' }}>Registry Directory</h1>
              <p style={{ color: 'var(--text-secondary)', fontSize: '1rem' }}>Central catalog of all registered internal enterprise AI agents.</p>
            </div>

            <form onSubmit={handleSearch} className="search-container">
              <input 
                type="text" 
                className="search-input"
                placeholder="Search agents semantically (e.g. 'I need to check employee payroll')"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
              />
              <button type="submit" className="btn btn-primary"><SearchIcon /><span>Search</span></button>
            </form>

            <div className="category-pills">
              {['All', 'Legal', 'HR', 'Finance', 'DevTools'].map(cat => (
                <span key={cat} className={`pill ${selectedCategory === cat ? 'active' : ''}`} onClick={() => setSelectedCategory(cat)}>
                  {cat}
                </span>
              ))}
            </div>

            {loading ? (
              <div style={{ padding: '4rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Querying database...</div>
            ) : (
              <div className="agent-grid">
                {filteredAgents.length === 0 ? (
                  <div style={{ gridColumn: '1/-1', padding: '4rem', textAlign: 'center', color: 'var(--text-secondary)', border: '1px dashed var(--border-color)', borderRadius: 'var(--radius-md)' }}>
                    No matching agents found. Try registering a new manifest!
                  </div>
                ) : (
                  filteredAgents.map(agent => (
                    <div key={agent.id} className="agent-card" onClick={() => setSelectedAgent(agent)}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <div>
                          <h3 style={{ fontSize: '1.2rem', fontWeight: '600', marginBottom: '0.2rem' }}>{agent.name}</h3>
                          <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>v{agent.version}</span>
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.3rem' }}>
                          <span className={`badge ${agent.status === 'ACTIVE' ? 'badge-active' : 'badge-unhealthy'}`}>
                            {agent.status}
                          </span>
                          {agent.requiresRbac && (
                            <span className="badge" style={{ backgroundColor: 'rgba(239, 68, 68, 0.08)', color: '#ef4444', border: '1px solid rgba(239, 68, 68, 0.15)' }}>
                              RBAC Lock
                            </span>
                          )}
                        </div>
                      </div>
                      <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', flexGrow: 1, lineBreak: 'anywhere' }}>{agent.description}</p>
                      {agent.similarity < 1.0 && agent.similarity > 0 && (
                        <div style={{ fontSize: '0.8rem', color: 'var(--accent-primary)', fontWeight: '500' }}>
                          Match Score: {Math.round(agent.similarity * 100)}%
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            )}

            {/* Agent Detail Modal */}
            {selectedAgent && (
              <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }} onClick={() => setSelectedAgent(null)}>
                <div className="glass-card" style={{ width: '100%', maxWidth: '550px', padding: '2.5rem', display: 'flex', flexDirection: 'column', gap: '1.5rem', margin: '1rem', background: 'var(--bg-secondary)', borderColor: 'var(--border-color)' }} onClick={e => e.stopPropagation()}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <h2 style={{ fontSize: '1.6rem' }}>{selectedAgent.name}</h2>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      {selectedAgent.requiresRbac && <span className="badge" style={{ backgroundColor: 'rgba(239, 68, 68, 0.1)', color: '#ef4444' }}>RBAC</span>}
                      <span className="badge badge-active">{selectedAgent.status}</span>
                    </div>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.4rem', fontSize: '0.85rem', textTransform: 'uppercase' }}>Description</h4>
                    <p style={{ fontSize: '0.95rem', lineHeight: '1.4' }}>{selectedAgent.description}</p>
                  </div>
                  {selectedAgent.requiresRbac && (
                    <div>
                      <h4 style={{ color: '#ef4444', marginBottom: '0.4rem', fontSize: '0.85rem', textTransform: 'uppercase' }}>Governance Rules</h4>
                      <p style={{ fontSize: '0.9rem' }}>
                        Execution is locked to user identities with active roles: 
                        <strong style={{ marginLeft: '0.4rem' }}>{selectedAgent.authorizedRoles?.join(', ') || 'None'}</strong>
                      </p>
                    </div>
                  )}
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.4rem', fontSize: '0.85rem', textTransform: 'uppercase' }}>Endpoints</h4>
                    <div style={{ fontFamily: 'monospace', fontSize: '0.8rem', display: 'flex', flexDirection: 'column', gap: '0.25rem', backgroundColor: 'var(--bg-primary)', padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border-color)' }}>
                      <div>Execution: <span style={{ color: 'var(--accent-primary)' }}>{selectedAgent.endpointUrl}</span></div>
                      <div>Health: <span style={{ color: 'var(--accent-primary)' }}>{selectedAgent.healthCheckUrl}</span></div>
                    </div>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.5rem', fontSize: '0.85rem', textTransform: 'uppercase' }}>Routing Parameters</h4>
                    {selectedAgent.parameters && selectedAgent.parameters.length > 0 ? (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid var(--border-color)', textAlign: 'left', color: 'var(--text-secondary)' }}>
                            <th style={{ padding: '0.5rem 0' }}>Name</th>
                            <th style={{ padding: '0.5rem 0' }}>Type</th>
                            <th style={{ padding: '0.5rem 0' }}>Required</th>
                          </tr>
                        </thead>
                        <tbody>
                          {selectedAgent.parameters.map(p => (
                            <tr key={p.name} style={{ borderBottom: '1px solid var(--border-color)' }}>
                              <td style={{ padding: '0.5rem 0', fontFamily: 'monospace' }}>{p.name}</td>
                              <td style={{ padding: '0.5rem 0', color: 'var(--accent-primary)' }}>{p.type}</td>
                              <td style={{ padding: '0.5rem 0' }}>{p.required ? 'Yes' : 'No'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>No parameter configuration specified.</p>
                    )}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                    <button className="btn btn-secondary" onClick={() => setSelectedAgent(null)}>Close</button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {/* VIEW 2: ONBOARDING */}
        {view === 'onboard' && (
          <>
            <div>
              <h1 style={{ fontSize: '2.5rem', fontWeight: '700', marginBottom: '0.5rem' }}>Agent Registration</h1>
              <p style={{ color: 'var(--text-secondary)', fontSize: '1rem' }}>Upload `agent-manifest.json` configurations to index your tool into the registry.</p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr', gap: '2.5rem', alignItems: 'start' }}>
              <form onSubmit={handleOnboard} className="glass-card" style={{ padding: '2rem', display: 'flex', flexDirection: 'column', gap: '1.25rem', background: 'var(--bg-secondary)' }}>
                <div className="form-group">
                  <label>Agent Manifest JSON Spec</label>
                  <textarea 
                    rows="15" 
                    className="form-control"
                    style={{ fontFamily: 'monospace', fontSize: '0.85rem', resize: 'vertical' }}
                    value={manifestText}
                    onChange={e => setManifestText(e.target.value)}
                  />
                </div>
                <button type="submit" className="btn btn-primary" style={{ justifyContent: 'center' }}>
                  <UploadIcon />
                  <span>Upload & Register</span>
                </button>
              </form>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                <h3 style={{ fontSize: '1.1rem', fontWeight: '600' }}>Verification Terminal</h3>
                <div className="terminal-console">
                  {onboardLogs.length === 0 ? (
                    <div style={{ color: 'var(--text-secondary)', fontStyle: 'italic' }}>Logs will display here...</div>
                  ) : (
                    onboardLogs.map((log, idx) => (
                      <div key={idx} className={`terminal-line ${log.type}`}>
                        <span style={{ color: 'var(--text-secondary)', marginRight: '0.5rem' }}>[{log.time}]</span>
                        <span>{log.text}</span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          </>
        )}

        {/* VIEW 3: SUPERVISOR CHAT */}
        {view === 'chat' && (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div>
                <h1 style={{ fontSize: '2.5rem', fontWeight: '700', marginBottom: '0.5rem' }}>Supervisor Orchestrator</h1>
                <p style={{ color: 'var(--text-secondary)', fontSize: '1rem' }}>Explain your requirement. The Supervisor will automatically resolve and route payload to matching agents.</p>
              </div>
              <div style={{ padding: '0.5rem 1rem', borderRadius: 'var(--radius-sm)', backgroundColor: 'var(--bg-secondary)', border: '1px solid var(--border-color)', fontSize: '0.85rem' }}>
                SSO Header: <strong style={{ color: 'var(--accent-primary)' }}>{userRole}</strong>
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1.3fr 0.7fr', gap: '2.5rem', height: '520px', alignItems: 'stretch' }}>
              
              {/* Chat Thread Panel */}
              <div className="glass-card" style={{ padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1rem', background: 'var(--bg-secondary)', overflow: 'hidden' }}>
                
                {/* Message display scroll panel */}
                <div style={{ flexGrow: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '1rem', paddingRight: '0.5rem' }}>
                  {chatMessages.map((msg, idx) => (
                    <div 
                      key={idx} 
                      style={{ 
                        alignSelf: msg.sender === 'user' ? 'flex-end' : 'flex-start',
                        backgroundColor: msg.sender === 'user' ? 'var(--accent-glow)' : 'var(--bg-primary)',
                        border: '1px solid var(--border-color)',
                        borderRadius: 'var(--radius-md)',
                        padding: '1rem',
                        maxWidth: '85%',
                        cursor: msg.routing ? 'pointer' : 'default'
                      }}
                      onClick={() => msg.routing && setSelectedRoutingMetadata(msg.routing)}
                    >
                      <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginBottom: '0.25rem' }}>
                        {msg.sender === 'user' ? 'You' : 'Supervisor'} • {msg.time}
                      </div>
                      
                      <p style={{ fontSize: '0.95rem', lineHeight: '1.4', whiteSpace: 'pre-wrap' }}>{msg.text}</p>
                      
                      {/* PII Shield Sanitization Badge */}
                      {msg.piiSanitized && (
                        <div style={{ 
                          marginTop: '0.6rem', 
                          display: 'flex', 
                          alignItems: 'center', 
                          gap: '0.4rem', 
                          fontSize: '0.75rem', 
                          color: '#b45309', 
                          backgroundColor: '#fef3c7', 
                          border: '1px solid #fcd34d',
                          padding: '0.35rem 0.6rem', 
                          borderRadius: '4px',
                          fontWeight: '500'
                        }}>
                          <span>🛡️ PII Firewall active: Sanitized elements ({msg.sanitizedFields?.join(', ')})</span>
                        </div>
                      )}

                      {msg.routing && (
                        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.5rem' }}>
                          <span style={{ fontSize: '0.75rem', color: 'var(--accent-primary)', fontWeight: 'bold' }}>
                            [Inspect Routing Log]
                          </span>
                        </div>
                      )}
                    </div>
                  ))}
                  {chatLoading && (
                    <div style={{ alignSelf: 'flex-start', color: 'var(--text-secondary)', fontSize: '0.9rem', fontStyle: 'italic' }}>
                      Supervisor is routing payload...
                    </div>
                  )}
                </div>

                {/* Input Text Form */}
                <form onSubmit={handleChatSend} style={{ display: 'flex', gap: '0.75rem' }}>
                  <input 
                    type="text" 
                    className="search-input"
                    placeholder="Describe requirement (e.g. 'extract text from document and translate it')"
                    value={chatInput}
                    onChange={e => setChatInput(e.target.value)}
                  />
                  <button type="submit" className="btn btn-primary">Send</button>
                </form>
              </div>

              {/* Execution Inspector Side Panel */}
              <div className="glass-card" style={{ padding: '1.5rem', background: 'var(--bg-secondary)', display: 'flex', flexDirection: 'column', gap: '1rem', overflowY: 'auto' }}>
                <h3 style={{ fontSize: '1.1rem', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem' }}>Execution Inspector</h3>
                
                {selectedRoutingMetadata ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', fontSize: '0.9rem' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span>Status:</span>
                      <span className={`badge ${selectedRoutingMetadata.status === 'routed' ? 'badge-active' : (selectedRoutingMetadata.status === 'forbidden' ? 'badge-unhealthy' : 'badge-unhealthy')}`}>
                        {selectedRoutingMetadata.status}
                      </span>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Routed Agent:</span>
                      <div style={{ fontWeight: '600', marginTop: '0.2rem' }}>{selectedRoutingMetadata.agent || 'None (No Match)'}</div>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Execution Step Trace:</span>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', marginTop: '0.4rem' }}>
                        {selectedRoutingMetadata.executionSteps && selectedRoutingMetadata.executionSteps.length > 0 ? (
                          selectedRoutingMetadata.executionSteps.map((step, sidx) => (
                            <div key={sidx} style={{ 
                              fontSize: '0.8rem', 
                              padding: '0.5rem 0.75rem', 
                              backgroundColor: 'var(--bg-primary)', 
                              borderRadius: '4px',
                              borderLeft: '3px solid var(--accent-primary)',
                              lineHeight: '1.3'
                            }}>
                              {step}
                            </div>
                          ))
                        ) : (
                          <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>No steps generated.</div>
                        )}
                      </div>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Latency:</span>
                      <div style={{ fontWeight: '500', marginTop: '0.2rem' }}>{selectedRoutingMetadata.latency} ms</div>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Extracted JSON Parameters:</span>
                      <pre style={{ 
                        marginTop: '0.5rem', 
                        fontFamily: 'monospace', 
                        fontSize: '0.8rem', 
                        backgroundColor: 'var(--bg-primary)', 
                        padding: '0.75rem', 
                        borderRadius: 'var(--radius-sm)', 
                        border: '1px solid var(--border-color)',
                        overflowX: 'auto',
                        whiteSpace: 'pre-wrap'
                      }}>
                        {JSON.stringify(selectedRoutingMetadata.params, null, 2)}
                      </pre>
                    </div>
                  </div>
                ) : (
                  <div style={{ color: 'var(--text-secondary)', fontStyle: 'italic', fontSize: '0.9rem', textAlign: 'center', marginTop: '4rem' }}>
                    Select a routed message response to inspect payload metadata.
                  </div>
                )}
              </div>

            </div>
          </>
        )}

        {/* VIEW 4: DEMAND & GAPS */}
        {view === 'analytics' && (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <h1 style={{ fontSize: '2.5rem', fontWeight: '700', marginBottom: '0.5rem' }}>Demand & Innovation Gaps</h1>
                <p style={{ color: 'var(--text-secondary)', fontSize: '1rem' }}>Data-driven corporate backlog mapped from unaddressed search queries.</p>
              </div>
              <button 
                className="btn btn-secondary" 
                onClick={triggerClustering}
                disabled={clusteringLoading}
              >
                {clusteringLoading ? 'Clustering...' : 'Trigger Gap Clustering'}
              </button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr', gap: '2.5rem', alignItems: 'start' }}>
              
              {/* Leaderboard Gaps Table */}
              <div className="glass-card" style={{ padding: '2rem', background: 'var(--bg-secondary)' }}>
                <h3 style={{ fontSize: '1.2rem', marginBottom: '1rem' }}>Capability Gaps Leaderboard</h3>
                {gaps.length === 0 ? (
                  <p style={{ color: 'var(--text-secondary)', fontStyle: 'italic', textAlign: 'center', padding: '2rem' }}>
                    No unaddressed capability gaps recorded yet.
                  </p>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem', textAlign: 'left' }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}>
                        <th style={{ padding: '0.75rem 0.5rem' }}>Capability Theme</th>
                        <th style={{ padding: '0.75rem 0.5rem' }}>Summary Details</th>
                        <th style={{ padding: '0.75rem 0.5rem', textAlign: 'center' }}>Weekly Hits</th>
                      </tr>
                    </thead>
                    <tbody>
                      {gaps.map(g => (
                        <tr key={g.id} style={{ borderBottom: '1px solid var(--border-color)' }}>
                          <td style={{ padding: '1rem 0.5rem', fontWeight: '600' }}>{g.title}</td>
                          <td style={{ padding: '1rem 0.5rem', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>{g.summary}</td>
                          <td style={{ padding: '1rem 0.5rem', textAlign: 'center', fontWeight: 'bold', color: 'var(--accent-primary)' }}>{g.countFrequency}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>

              {/* Telemetry Metrics List */}
              <div className="glass-card" style={{ padding: '2rem', background: 'var(--bg-secondary)', display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
                <h3 style={{ fontSize: '1.2rem' }}>Registered Agent Telemetry</h3>
                {telemetry.length === 0 ? (
                  <p style={{ color: 'var(--text-secondary)', fontStyle: 'italic', textAlign: 'center' }}>No execution telemetry logs found.</p>
                ) : (
                  telemetry.map((t, idx) => (
                    <div key={idx} style={{ borderBottom: '1px solid var(--border-color)', paddingBottom: '1rem' }}>
                      <div style={{ fontWeight: '600', marginBottom: '0.5rem', fontSize: '0.95rem' }}>
                        Agent Key: <span style={{ fontFamily: 'monospace', color: 'var(--accent-primary)' }}>{t.agentid}</span>
                      </div>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem', fontSize: '0.85rem' }}>
                        <div>Runs Count: <strong>{t.countruns}</strong></div>
                        <div>Success Rate: <strong>{Math.round(t.successrate)}%</strong></div>
                        <div>Avg Latency: <strong>{Math.round(t.avglatency)} ms</strong></div>
                        <div>Token Cost: <strong>${parseFloat(t.totalcost).toFixed(4)}</strong></div>
                      </div>
                    </div>
                  ))
                )}
              </div>

            </div>
          </>
        )}

      </main>
    </div>
  );
}

export default App;
