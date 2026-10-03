import { useState } from "react";
import { API_URL, DEV_USER_ID } from "../services/api";

function TaskForm({ onTaskCreated }) {
  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState("Medium");
  const [duration, setDuration] = useState("");
  const [deadline, setDeadline] = useState("");

  const handleSubmit = async (e) => {
    e.preventDefault();

    try {
      const response = await fetch(`${API_URL}/tasks`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          userId: DEV_USER_ID,
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
    } catch (error) {
      console.error("Task creation failed:", error);
    }
  };

  return (
    <form className="task-form" onSubmit={handleSubmit}>
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

      <button type="submit">Add Task</button>
    </form>
  );
}

export default TaskForm;