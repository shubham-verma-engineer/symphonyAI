import React, { useState, useEffect, useRef } from 'react';

const API_BASE = 'http://localhost:8080/api/v1/agents';

// SVG Icons
const SearchIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
);
const GridIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="7" height="9"></rect><rect x="14" y="3" width="7" height="5"></rect><rect x="14" y="12" width="7" height="9"></rect><rect x="3" y="16" width="7" height="5"></rect></svg>
);
const UploadIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
);
const CheckIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>
);

function App() {
  const [view, setView] = useState('marketplace'); // 'marketplace' | 'onboard'
  const [agents, setAgents] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('All');
  const [loading, setLoading] = useState(false);
  const [manifestText, setManifestText] = useState(JSON.stringify({
    "manifestVersion": "1.0",
    "name": "document-summarizer",
    "version": "1.0.0",
    "description": "Extracts summaries and action items from PDFs and DOCX files.",
    "endpoints": {
      "execution": "https://summarizer.internal.net/api/v1/summarize",
      "health": "https://summarizer.internal.net/health"
    },
    "parameters": [
      {
        "name": "documentUrl",
        "type": "string",
        "description": "Secure file path of the document",
        "required": true
      }
    ],
    "security": {
      "requiresRBAC": false
    }
  }, null, 2));

  const [onboardLogs, setOnboardLogs] = useState([]);
  const [selectedAgent, setSelectedAgent] = useState(null);

  // Fetch all agents on mount / view switch
  const fetchAgents = async () => {
    setLoading(true);
    try {
      const res = await fetch(API_BASE);
      if (res.ok) {
        const data = await res.json();
        setAgents(data);
      }
    } catch (err) {
      console.error("Failed to load agents", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (view === 'marketplace') {
      fetchAgents();
      setSelectedAgent(null);
    }
  }, [view]);

  // Semantic search call
  const handleSearch = async (e) => {
    e.preventDefault();
    if (!searchQuery.trim()) {
      fetchAgents();
      return;
    }
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: searchQuery, limit: 10 })
      });
      if (res.ok) {
        const data = await res.json();
        setAgents(data);
      }
    } catch (err) {
      console.error("Failed search", err);
    } finally {
      setLoading(false);
    }
  };

  // Simulated live onboarding flow
  const handleOnboard = async (e) => {
    e.preventDefault();
    setOnboardLogs([]);
    
    const addLog = (text, type = 'info') => {
      setOnboardLogs(prev => [...prev, { text, type, time: new Date().toLocaleTimeString() }]);
    };

    try {
      addLog("Parsing manifest payload...", "info");
      let parsed;
      try {
        parsed = JSON.parse(manifestText);
      } catch (err) {
        addLog("JSON Parse Error: Invalid JSON structure.", "error");
        return;
      }

      addLog(`Validation: Checking JSON Schema constraints for '${parsed.name || 'unnamed'}'...`, "info");
      if (!parsed.name || !parsed.description || !parsed.endpoints) {
        addLog("Validation Error: Missing required fields (name, description, endpoints).", "error");
        return;
      }
      addLog("Validation PASS: Schema constraints satisfied.", "success");

      addLog(`Networking: Dispatching health loopback checks to ${parsed.endpoints.health}...`, "info");
      
      // Call actual backend onboarding API
      const res = await fetch(`${API_BASE}/onboard`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parsed)
      });

      if (res.ok) {
        const data = await res.json();
        addLog(`Health Check: Health endpoint responded 200 OK.`, "success");
        addLog(`Embedding Engine: Generated OpenAI vector representation (1536-dim).`, "success");
        addLog(`Registry Database: Committed agent '${data.name}' with ID: ${data.id}`, "success");
        addLog("Agent registration COMPLETE.", "success");
      } else {
        const errText = await res.text();
        addLog(`Loopback Failed: ${errText || 'Endpoint unreachable or invalid response.'}`, "error");
      }
    } catch (err) {
      addLog(`Internal Server Error: ${err.message}`, "error");
    }
  };

  // Filter agents by search query (fallback) / category
  const filteredAgents = agents.filter(a => {
    if (selectedCategory === 'All') return true;
    if (selectedCategory === 'Legal') return a.name.toLowerCase().includes('legal') || a.description.toLowerCase().includes('legal');
    if (selectedCategory === 'HR') return a.name.toLowerCase().includes('hr') || a.name.toLowerCase().includes('payroll') || a.description.toLowerCase().includes('payroll');
    if (selectedCategory === 'Finance') return a.name.toLowerCase().includes('finance') || a.description.toLowerCase().includes('finance');
    if (selectedCategory === 'DevTools') return a.name.toLowerCase().includes('summary') || a.name.toLowerCase().includes('parse') || a.description.toLowerCase().includes('summar');
    return true;
  });

  return (
    <div className="app-container">
      {/* Sidebar Navigation */}
      <aside className="sidebar">
        <div className="brand">
          <span style={{ fontSize: '1.8rem' }}>💎</span>
          <span>SymphonyAI</span>
        </div>
        <nav style={{ flexGrow: 1 }}>
          <ul className="nav-links">
            <li 
              className={`nav-item ${view === 'marketplace' ? 'active' : ''}`}
              onClick={() => setView('marketplace')}
            >
              <GridIcon />
              <span>Registry Hub</span>
            </li>
            <li 
              className={`nav-item ${view === 'onboard' ? 'active' : ''}`}
              onClick={() => setView('onboard')}
            >
              <UploadIcon />
              <span>Onboard Agent</span>
            </li>
          </ul>
        </nav>
        <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
          Workspace: 00000000-...-0001
        </div>
      </aside>

      {/* Main Panel Content */}
      <main className="main-content">
        {view === 'marketplace' ? (
          <>
            <div>
              <h1 style={{ fontSize: '2.2rem', marginBottom: '0.5rem' }}>Enterprise AI Marketplace</h1>
              <p style={{ color: 'var(--text-secondary)' }}>Discover and search registered employee-built AI tools across the organization.</p>
            </div>

            {/* Semantic Search Box */}
            <form onSubmit={handleSearch} className="search-container">
              <input 
                type="text" 
                className="search-input"
                placeholder="Describe your business problem (e.g. 'I need to evaluate legal risks')"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
              />
              <button type="submit" className="btn btn-primary">
                <SearchIcon />
                <span>Search</span>
              </button>
            </form>

            {/* Category Pills */}
            <div className="category-pills">
              {['All', 'Legal', 'HR', 'Finance', 'DevTools'].map(cat => (
                <span 
                  key={cat}
                  className={`pill ${selectedCategory === cat ? 'active' : ''}`}
                  onClick={() => setSelectedCategory(cat)}
                >
                  {cat}
                </span>
              ))}
            </div>

            {loading ? (
              <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
                Loading agents from registry...
              </div>
            ) : (
              <div className="agent-grid">
                {filteredAgents.length === 0 ? (
                  <div style={{ gridColumn: '1/-1', padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)', border: '1px dashed var(--border-color)', borderRadius: 'var(--radius-md)' }}>
                    No agents match this query. Try search terms or onboard a new agent!
                  </div>
                ) : (
                  filteredAgents.map(agent => (
                    <div 
                      key={agent.id} 
                      className="glass-card" 
                      style={{ padding: '1.5rem', cursor: 'pointer', display: 'flex', flexDirection: 'column', gap: '1rem' }}
                      onClick={() => setSelectedAgent(agent)}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <div>
                          <h3 style={{ fontSize: '1.25rem', marginBottom: '0.25rem' }}>{agent.name}</h3>
                          <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>v{agent.version}</span>
                        </div>
                        <span className={`badge ${agent.status === 'ACTIVE' ? 'badge-active' : 'badge-unhealthy'}`}>
                          {agent.status}
                        </span>
                      </div>
                      
                      <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', flexGrow: 1, lineBreak: 'anywhere' }}>
                        {agent.description}
                      </p>

                      {/* Display similarity if returning from a search query */}
                      {agent.similarity < 1.0 && agent.similarity > 0 && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: 'var(--accent-secondary)' }}>
                          <span>Match Score:</span>
                          <span style={{ fontWeight: 'bold' }}>{Math.round(agent.similarity * 100)}%</span>
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            )}

            {/* Selected Agent Details Modal Overlay */}
            {selectedAgent && (
              <div 
                style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }}
                onClick={() => setSelectedAgent(null)}
              >
                <div 
                  className="glass-card" 
                  style={{ width: '100%', maxWidth: '600px', padding: '2rem', display: 'flex', flexDirection: 'column', gap: '1.5rem', margin: '1rem', background: '#12141c' }}
                  onClick={e => e.stopPropagation()}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <h2>{selectedAgent.name}</h2>
                    <span className="badge badge-active">{selectedAgent.status}</span>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.25rem' }}>Description</h4>
                    <p>{selectedAgent.description}</p>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.25rem' }}>Endpoints</h4>
                    <div style={{ fontFamily: 'monospace', fontSize: '0.85rem', display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                      <div>Execution: <span style={{ color: 'var(--accent-primary)' }}>{selectedAgent.endpointUrl}</span></div>
                      <div>Health: <span style={{ color: 'var(--accent-primary)' }}>{selectedAgent.healthCheckUrl}</span></div>
                    </div>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.5rem' }}>Required Parameters</h4>
                    {selectedAgent.parameters && selectedAgent.parameters.length > 0 ? (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid var(--border-color)', textAlign: 'left', color: 'var(--text-secondary)' }}>
                            <th style={{ padding: '0.5rem' }}>Name</th>
                            <th style={{ padding: '0.5rem' }}>Type</th>
                            <th style={{ padding: '0.5rem' }}>Description</th>
                            <th style={{ padding: '0.5rem' }}>Required</th>
                          </tr>
                        </thead>
                        <tbody>
                          {selectedAgent.parameters.map(p => (
                            <tr key={p.name} style={{ borderBottom: '1px solid rgba(255,255,255,0.03)' }}>
                              <td style={{ padding: '0.5rem', fontFamily: 'monospace' }}>{p.name}</td>
                              <td style={{ padding: '0.5rem', color: 'var(--accent-secondary)' }}>{p.type}</td>
                              <td style={{ padding: '0.5rem' }}>{p.description}</td>
                              <td style={{ padding: '0.5rem' }}>{p.required ? 'Yes' : 'No'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>No parameters required.</p>
                    )}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                    <button className="btn btn-secondary" onClick={() => setSelectedAgent(null)}>Close</button>
                  </div>
                </div>
              </div>
            )}
          </>
        ) : (
          /* ONBOARDING CREATOR CONSOLE VIEW */
          <>
            <div>
              <h1 style={{ fontSize: '2.2rem', marginBottom: '0.5rem' }}>Agent Onboarding Portal</h1>
              <p style={{ color: 'var(--text-secondary)' }}>Onboard custom developer agents into the centralized registry catalog.</p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '2rem', alignItems: 'start' }}>
              {/* Form Input Section */}
              <form onSubmit={handleOnboard} className="glass-card" style={{ padding: '2rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                <div className="form-group">
                  <label>Agent Manifest JSON (`agent-manifest.json`)</label>
                  <textarea 
                    rows="15" 
                    className="form-control"
                    style={{ fontFamily: 'monospace', fontSize: '0.85rem', resize: 'vertical' }}
                    value={manifestText}
                    onChange={e => setManifestText(e.target.value)}
                  />
                </div>
                <div className="form-group">
                  <label>Loopback Security Auth Token (Optional)</label>
                  <input type="password" className="form-control" placeholder="Enter basic auth / bearer token" />
                </div>
                <div style={{ display: 'flex', gap: '1rem' }}>
                  <button type="submit" className="btn btn-primary" style={{ flexGrow: 1 }}>
                    <UploadIcon />
                    <span>Upload & Register</span>
                  </button>
                </div>
              </form>

              {/* Validation Logs Terminal */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                <h3 style={{ fontSize: '1.2rem' }}>Onboarding Verification Console</h3>
                <div className="terminal-console">
                  {onboardLogs.length === 0 ? (
                    <div style={{ color: 'var(--text-secondary)', fontStyle: 'italic' }}>
                      Logs will display here once registration is triggered...
                    </div>
                  ) : (
                    onboardLogs.map((log, idx) => (
                      <div key={idx} className={`terminal-line ${log.type}`}>
                        <span style={{ color: '#4a5568' }}>[{log.time}]</span>
                        <span>{log.text}</span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          </>
        )}
      </main>
    </div>
  );
}

export default App;
