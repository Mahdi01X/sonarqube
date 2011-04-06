#
# Sonar, entreprise quality control tool.
# Copyright (C) 2008-2011 SonarSource
# mailto:contact AT sonarsource DOT com
#
# Sonar is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 3 of the License, or (at your option) any later version.
#
# Sonar is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with Sonar; if not, write to the Free Software
# Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
#

class ReviewsController < ApplicationController

	SECTION=Navigation::SECTION_CONFIGURATION
	
	def index
	end
	
	def list
	  reviews = Review.find :all, :conditions => ['rule_failure_id=?', params[:rule_failure_id]]
	  render :partial => "list", :locals => { :reviews => reviews }, :layout => false
	end
	
	def form
	  @review = Review.new
	  @review.rule_failure_id = params[:violation_id]
	  @review.user = current_user
	  @review_comment = ReviewComment.new
	  @review_comment.user = current_user
	  @review_comment.review = @review
	  @review_comment.review_text = "Enter your review here"
	  render "_form", :layout => false
	end
	
	def formComment
	  @review_comment = ReviewComment.new
	  @review_comment.user = current_user
	  @review_comment.review_id = params[:review_id]
	  @review_comment.review_text = ""
	  @rule_failure_id = params[:rule_failure_id]
	  render "_form_comment", :layout => false
	end
	
	def create
	  review = Review.new(params[:review])
	  review.user = current_user
	  review.save
	  #review.review_comments.create(params[:review_comment])
      review_comment = ReviewComment.new(params[:review_comment])
	  review_comment.user = current_user
	  review_comment.review_id = review.id
	  review_comment.save
	  
	  params[:rule_failure_id] = review.rule_failure_id
	  list
	end
	
	def createComment
      review_comment = ReviewComment.new(params[:review_comment])
      review_comment.user = current_user
	  review_comment.save
	  
	  list
	end
	
end
