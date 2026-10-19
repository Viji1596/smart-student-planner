import { useState } from "react";
import { API_URL } from "../services/api";

function TaskForm({ onTaskCreated }) {
  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState("Medium");
  const [duration, setDuration] = useState("");
  const [deadline, setDeadline] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const response = await fetch(`${API_URL}/tasks`, {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          title,
          priority,
          estimatedMinutes: duration,
          dueDate: deadline,
        }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || "Failed to create task");
      }

      console.log("Task created:", data);

      setTitle("");
      setPriority("Medium");
      setDuration("");
      setDeadline("");

      if (onTaskCreated) {
        onTaskCreated();
      }
    } catch (err) {
      console.error("Task creation failed:", err);
      setError(err.message || "Failed to create task");
    } finally {
      setLoading(false);
    }
  };

  return (
    <form className="task-form" onSubmit={handleSubmit}>
      {error && <p className="form-error" style={{ color: "#ef4444", marginBottom: "1rem" }}>{error}</p>}

      <label>
        Task Title
        <input
          type="text"
          placeholder="Finish assignment"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          required
        />
      </label>

      <label>
        Priority
        <select
          value={priority}
          onChange={(e) => setPriority(e.target.value)}
        >
          <option value="Low">Low</option>
          <option value="Medium">Medium</option>
          <option value="High">High</option>
        </select>
      </label>

      <label>
        Duration (minutes)
        <input
          type="number"
          placeholder="e.g. 60"
          min="1"
          value={duration}
          onChange={(e) => setDuration(e.target.value)}
          required
        />
      </label>

      <label>
        Deadline
        <input
          type="datetime-local"
          value={deadline}
          onChange={(e) => setDeadline(e.target.value)}
        />
      </label>

      <button type="submit" disabled={loading}>
        {loading ? "Adding Task..." : "Add Task"}
      </button>
    </form>
  );
}

export default TaskForm;