import { useEffect, useState } from "react";
import { API_URL } from "../services/api";

function ScheduleSummary() {
  const [sessionCount, setSessionCount] = useState(0);

  useEffect(() => {
    const fetchSessions = async () => {
      try {
        const response = await fetch(`${API_URL}/schedule`);
        const sessions = await response.json();

        if (response.ok) {
          setSessionCount(sessions.length);
        }
      } catch (error) {
        console.error("Failed to fetch schedule sessions:", error);
      }
    };

    fetchSessions();
  }, []);

  return (
    <div className="dashboard-card">
      <h2>Schedule</h2>
      <p>{sessionCount} scheduled sessions</p>
    </div>
  );
}

export default ScheduleSummary;