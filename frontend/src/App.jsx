import { useState } from "react";
import ThemeToggle from "./components/ThemeToggle";
import Dashboard from "./pages/Dashboard";
import Tasks from "./pages/Tasks";

function App() {
  const [page, setPage] = useState("dashboard");

  return (
    <>
      <nav>
        <button onClick={() => setPage("dashboard")}>Dashboard</button>
        <button onClick={() => setPage("tasks")}>Tasks</button>
      </nav>

      {page === "dashboard" ? <Dashboard /> : <Tasks />}

      <div className="theme-toggle">
        <ThemeToggle />
      </div>
    </>
  );
}

export default App;