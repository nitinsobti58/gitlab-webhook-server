package com.example.webhook;

public class PipelineEvent {
    private String project;
    private String branch;
    private String status;
    private long pipelineId;
    private String triggeredBy;
    private String commitMessage;

    public PipelineEvent(String project, String branch, String status, long pipelineId, String triggeredBy, String commitMessage) {
        this.project = project;
        this.branch = branch;
        this.status = status;
        this.pipelineId = pipelineId;
        this.triggeredBy = triggeredBy;
        this.commitMessage = commitMessage;
    }

    // Getters
    public String getProject() { return project; }
    public String getBranch() { return branch; }
    public String getStatus() { return status; }
    public long getPipelineId() { return pipelineId; }
    public String getTriggeredBy() { return triggeredBy; }
    public String getCommitMessage() { return commitMessage; }

    // Setters (optional if you want immutability, you can remove them)
    public void setProject(String project) { this.project = project; }
    public void setBranch(String branch) { this.branch = branch; }
    public void setStatus(String status) { this.status = status; }
    public void setPipelineId(long pipelineId) { this.pipelineId = pipelineId; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }
}
