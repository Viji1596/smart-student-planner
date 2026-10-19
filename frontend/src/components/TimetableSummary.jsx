import { useEffect, useState } from "react";
import { API_URL } from "../services/api";

function TimetableSummary() {
  const [entryCount, setEntryCount] = useState(0);

  useEffect(() => {
    const fetchTimetable = async () => {
      try {
        const response = await fetch(`${API_URL}/timetable`, {
          credentials: "include",
        });
        const entries = await response.json();

        if (response.ok && Array.isArray(entries)) {
          setEntryCount(entries.length);
        }
      } catch (error) {
        console.error("Failed to fetch timetable:", error);
      }
    };

    fetchTimetable();
  }, []);

  return (
    <div className="dashboard-card">
      <h2>Timetable</h2>
      <p>{entryCount} timetable entries</p>
    </div>
  );
}

export default TimetableSummary;