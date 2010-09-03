package net.sf.redmine_mylyn.core;

public enum RedmineOperation {

	none(RedmineAttribute.STATUS, "Leave as %s", false, true),
	markas(RedmineAttribute.STATUS_CHG, "Mark as", true, false);
	
	private RedmineAttribute attribute;
	
	private String label;
	
	private boolean assiciated;
	
	private boolean restore;
	
	RedmineOperation(RedmineAttribute attribute, String label, boolean associated, boolean restoreDefault) {
		this.attribute = attribute;
		this.label = label;
		this.assiciated  = associated;
		this.restore = associated==false && restoreDefault;
	}
	
	public String getInputId() {
		return attribute.getTaskKey();
	}

	public String getLabel(Object... args) {
		if(args.length>0) {
			return String.format(label, args);
		}
		return label;
	}
	
	public String getType() {
		return attribute.getType();
	}

	public boolean isAssociated() {
		return assiciated;
	}
	
	public boolean needsRestoreValue() {
		return restore;
	}
	
}